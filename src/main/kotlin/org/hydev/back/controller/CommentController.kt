@file:Suppress("NAME_SHADOWING")

package org.hydev.back.controller

import com.github.kotlintelegrambot.dispatcher.handlers.HandleCallbackQuery
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.ParseMode
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.annotation.PreDestroy
import org.hydev.back.*
import org.hydev.back.ai.HarmLevel
import org.hydev.back.ai.IHarmClassifier
import org.hydev.back.db.Ban
import org.hydev.back.db.BanRepo
import org.hydev.back.db.PendingComment
import org.hydev.back.db.PendingCommentRepo
import org.hydev.back.geoip.AcceptLanguage
import org.hydev.back.geoip.GeoIP
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.HtmlUtils
import java.sql.Date
import java.util.concurrent.ConcurrentHashMap
import javax.servlet.http.HttpServletRequest


@RestController
@RequestMapping("/comment")
@CrossOrigin(origins = ["*"])
class CommentController(
    private val commentRepo: PendingCommentRepo,
    private val banRepo: BanRepo,
    private val geoIP: GeoIP,
    private val harmClassifier: IHarmClassifier
) {

    /**
     * Add or update note for a pending comment
     * @param commentId Comment ID
     * @param noteContent Note content (use "clear" to clear the note)
     * @return Success message or error message
     */
    fun addNote(commentId: Long, noteContent: String): String {
        val comment = commentRepo.queryById(commentId)
            ?: return "找不到评论 #$commentId"

        if (noteContent.lowercase() == "clear") {
            commentRepo.save(comment.apply { note = null })
            return "✅ 已清空评论 #$commentId 的备注"
        }

        commentRepo.save(comment.apply { note = noteContent })
        return "✅ 已为评论 #$commentId 添加备注：\n$noteContent"
    }

    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @PreDestroy
    fun onDestroy() {
        controllerScope.cancel("CommentController 正在关闭，取消所有未完成的审核任务")
    }

    private val processingCommentIds = ConcurrentHashMap.newKeySet<Long>()

    /**
     * Acquire the in-process lock for [id], then launch an IO coroutine to run [block].
     * - Lock taken     → answer callback with a busy alert and return immediately.
     * - Lock acquired  → silently acknowledge the callback (stops spinner), run [block] on IO dispatcher.
     * Errors inside [block] are caught, logged, and reported to the admin chat.
     * The lock is always released in `finally`.
     */
    private fun withCommentLock(
        bot: com.github.kotlintelegrambot.Bot,
        id: Long,
        callbackQueryId: String,
        chatId: ChatId,
        actionName: String,
        block: suspend CoroutineScope.() -> Unit
    ) {
        if (!processingCommentIds.add(id)) {
            bot.answerCallbackQuery(callbackQueryId, text = "⚠️ 有人正在处理这条评论，请稍候", showAlert = true)
            return
        }
        bot.answerCallbackQuery(callbackQueryId)
        controllerScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("[!] Failed to $actionName comment $id: $e")
                bot.sendMessage(chatId, "[!] #$id ${actionName}失败: $e")
            } finally {
                processingCommentIds.remove(id)
            }
        }
    }

    private val actionButtons = mapOf(
        "通过" to "pass",
        "Spoiler 后通过" to "pass-spoiler",
        "忽略" to "reject",
        "封禁 IP" to "ban"
    )

    val replyMarkup = InlineKeyboardMarkup.createSingleRowKeyboard(
        actionButtons.map { (text, action) ->
            InlineKeyboardButton.CallbackData(text = text, callbackData = "comment-$action")
        }
    )

    val commentCallback: HandleCallbackQuery = callback@ {
        val chatId = ChatId.fromId(callbackQuery.message!!.chat.id)
        val msgId = callbackQuery.message!!.messageId
        val inlId = callbackQuery.inlineMessageId
        val message = callbackQuery.message!!.text!!
        val id = message.split(" ")[0].substring(1).toLong()
        val data = callbackQuery.data

        val operator = callbackQuery.from.let { user ->
            user.username?.let { "@$it" } ?: user.firstName
        }

        if (data == "comment-cancel") {
            withCommentLock(bot, id, callbackQuery.id, chatId, "cancel") {
                bot.editMessageReplyMarkup(chatId, msgId, inlId, replyMarkup)
            }
            return@callback
        }

        if (data.startsWith("comment-confirm-")) {
            val action = data.removePrefix("comment-confirm-")

            when (action) {
                // Ban ip
                "ban" -> withCommentLock(bot, id, callbackQuery.id, chatId, "ban") {
                    val comment = commentRepo.queryById(id)!!
                    val ip = comment.ip
                    banRepo.save(Ban(ip = ip, reason = "Bad comment #$id"))
                    val banMessage = """
                        #$id - ${HtmlUtils.htmlEscape(comment.personId)} 收到了新的留言：
                        
                        <blockquote expandable><tg-spoiler>${HtmlUtils.htmlEscape(comment.content)}</tg-spoiler></blockquote>
                        
                        - 已封禁🚫 ${HtmlUtils.htmlEscape(ip)} by ${HtmlUtils.htmlEscape(operator)}
                    """.trimIndent()
                    println("[-] Comment banned! IP $ip banned by $operator due to Comment $id")
                    bot.editMessageText(chatId, msgId, inlId, banMessage, parseMode = ParseMode.HTML)
                }

                // Rejected, remove
                "reject" -> withCommentLock(bot, id, callbackQuery.id, chatId, "reject") {
                    println("[-] Comment rejected! Comment $id deleted by $operator")
                    bot.editMessageText(chatId, msgId, inlId, "$message\n- 已删除❌ by $operator")
                }

                // Commit changes
                "pass", "pass-spoiler" -> withCommentLock(bot, id, callbackQuery.id, chatId, "pass") {
                    var statusMsgId = 0L
                    try {
                        val comment = commentRepo.queryById(id)!!
                        if (comment.approved) {
                            // Already processed (e.g. approved before this server restarted), notify and bail
                            bot.sendMessage(chatId, "⚠️ 这条评论 (#$id) 已经被处理过了")
                            return@withCommentLock
                        }
                        bot.sendMessage(chatId, "正在提交更改...").fold(
                            { statusMsgId = it.messageId },
                            { System.err.println("> Failed to send submission message: $it") })

                        // Spoiler
                        if (action == "pass-spoiler")
                            comment.content = "||${comment.content.replace('\n', ' ')}||"

                        // Create commit content
                        val fPath = "people/${comment.personId}/comments/${date("yyyy-MM-dd")}-C${comment.id}.json"
                        val cMsg = "[+] Comment added by ${comment.submitter} for ${comment.personId}"

                        // Build JSON content with optional replies
                        val content = json("id" to comment.id, "content" to comment.content,
                            "submitter" to comment.submitter, "date" to comment.date,
                            *comment.note?.let { arrayOf("replies" to listOf(mapOf("content" to it, "submitter" to "Maintainer"))) } ?: arrayOf())
                        println("[+] Comment approved. Adding Comment $id: $content by $operator")

                        // Write commit
                        val url = commitDirectly(comment.submitter, DataEdit(fPath, content), cMsg)

                        // Update database
                        comment.approved = true
                        commentRepo.save(comment)

                        // Attach URL
                        bot.editMessageText(chatId, msgId, inlId, "$message\n- 已通过审核✅ by $operator", replyMarkup =
                            InlineKeyboardMarkup.createSingleRowKeyboard(
                                InlineKeyboardButton.Url(text = "查看 Commit", url = url)
                            )
                        )
                    } finally {
                        if (statusMsgId != 0L) bot.deleteMessage(chatId, statusMsgId)
                    }
                }
            }
            return@callback
        }

        val action = data.removePrefix("comment-")
        val confirmText = actionButtons.entries.find { it.value == action }?.key?.let { "✅ 确认$it" }
        val confirmMarkup = confirmText?.let {
            InlineKeyboardMarkup.createSingleRowKeyboard(
                InlineKeyboardButton.CallbackData(text = it, callbackData = "comment-confirm-$action"),
                InlineKeyboardButton.CallbackData(text = "❌ 取消", callbackData = "comment-cancel")
            )
        } ?: replyMarkup

        withCommentLock(bot, id, callbackQuery.id, chatId, "confirm-markup") {
            bot.editMessageReplyMarkup(chatId, msgId, inlId, confirmMarkup)
        }
    }

    @PostMapping("/add")
    suspend fun addComment(
        @P id: str, @P content: str, @P captcha: str, @P name: str, @P email: str,
        request: HttpServletRequest
    ): Any
    {
        val ip = request.getIP()
        println("""
[+] Comment received. 
> IP: $ip
> ID: $id
> Name: $name
> Email: $email
> Content: $content
> Accept-Language: ${request.getHeader("accept-language")}
> User-Agent: ${request.getHeader("user-agent")}
<< EOF >>""")

        // Verify captcha
        if (!verifyCaptcha(secrets.recaptchaSecret, captcha))
        {
            println("> Rejected: Cannot verify captcha")
            return "没有查到验证码".http(400)
        }

        // TODO: Check if id exists
        val name = name.ifBlank { "Anonymous" }
        val email = if (email.isBlank() || !email.isValidEmail())
            "anonymous@example.com" else email

        // Add to database
        val comment = withContext(Dispatchers.IO) {
            commentRepo.save(PendingComment(
                personId = id,
                content = content,
                submitter = name,
                email = email,
                date = Date(java.util.Date().time),
                ip = ip
            ))
        }

        var notif = """
#${comment.id} - $id 收到了新的留言：

$content

- IP: $ip"""

        if (name != "Anonymous")
            notif += "\n- 姓名: $name"
        if (email != "anonymous@example.com")
            notif += "\n- 邮箱: $email"
        geoIP.info(ip)?.let { notif += "\n$it" }

        // Check if ip is banned. If it is, send it to the blocked chat instead.
        val ban = banRepo.queryByIp(ip)
        val chatId = if (ban != null) {
            notif += "\n- ❌ IP 已被封禁！"
            secrets.telegramBlockedChatID
        }
        else {
            // Check if AI think it's inappropriate
            val clas = harmClassifier.classify(content)
            clas?.msg?.let { notif += "\n- $it" }

            if (clas == HarmLevel.HARMFUL) secrets.telegramBlockedChatID
            else secrets.telegramChatID
        }

        request.getHeader("accept-language")?.let { notif += "\n- 请求语言: ${AcceptLanguage.parse(it)}" }
        request.getHeader("user-agent")?.let { notif += "\n- 浏览器: $it" }

        // Send message on telegram
        bot.sendMessage(ChatId.fromId(chatId), notif, replyMarkup = replyMarkup)

        // Print log
        println("> Accepted, added to database.")
        println(notif)

        return "Success"
    }
}
