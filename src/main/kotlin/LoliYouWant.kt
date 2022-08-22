package top.mrxiaom.loliyouwant

import net.mamoe.mirai.console.command.CommandManager.INSTANCE.register
import net.mamoe.mirai.console.permission.PermissionId
import net.mamoe.mirai.console.permission.PermissionService
import net.mamoe.mirai.console.permission.PermissionService.Companion.testPermission
import net.mamoe.mirai.console.permission.PermitteeId.Companion.permitteeId
import net.mamoe.mirai.console.plugin.id
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.event.globalEventChannel
import net.mamoe.mirai.message.MessageReceipt
import net.mamoe.mirai.message.data.At
import net.mamoe.mirai.message.data.PlainText
import net.mamoe.mirai.message.data.QuoteReply
import net.mamoe.mirai.utils.info
import java.io.FileInputStream

object LoliYouWant : KotlinPlugin(
        JvmPluginDescription(
                id = "top.mrxiaom.loliyouwant",
                name = "Loli You Want",
                version = "0.1.0",
        ) {
            author("MrXiaoM")
        }
) {
    private val PERM_RANDOM = PermissionId(id, "random")
    private val PERM_BYPASS_COOLDOWN = PermissionId(id, "bypass.cooldown")
    private val PERM_RELOAD = PermissionId(id, "reload")
    private val cooldown = mutableMapOf<Long, Long>()
    override fun onEnable() {
        val permRandom = PermissionService.INSTANCE.register(PERM_RANDOM, "随机发图权限")
        val permBypassCooldown = PermissionService.INSTANCE.register(PERM_BYPASS_COOLDOWN, "绕过冷却时间")

        reloadConfig()
        LoliCommand(PermissionService.INSTANCE.register(PERM_RELOAD, "重载配置文件")).register()

        globalEventChannel(coroutineContext).subscribeAlways<GroupMessageEvent> {
            if (LoliConfig.at && this.message.filterIsInstance<At>().none { it.target == bot.id }) return@subscribeAlways
            if (!LoliConfig.enableGroups.contains(group.id) && !permRandom.testPermission(group.permitteeId)) return@subscribeAlways
            if (!LoliConfig.keywords.contains(message.filterIsInstance<PlainText>().joinToString { it.content }.trimStart().trimEnd())) return@subscribeAlways
            val replacement = mutableMapOf("quote" to QuoteReply(source), "at" to At(sender))
            if (!permBypassCooldown.testPermission(group.permitteeId) && !permBypassCooldown.testPermission(sender.permitteeId)) {
                val cd = cooldown.getOrDefault(group.id, 0)
                if (cd >= System.currentTimeMillis()) {
                    replacement["cd"] = PlainText(((cd - System.currentTimeMillis()) / 1000L).toString())
                    group.sendMessage(LoliConfig.replyCooldown.replace(replacement))
                    return@subscribeAlways
                }
            }
            cooldown[group.id] = System.currentTimeMillis() + LoliConfig.cooldown * 1000
            val receipt = group.sendMessage(LoliConfig.replyFetching.replace(replacement))
            val loli = searchLoli(Lolibooru.random(10))
            if (loli == null) {
                group.sendMessage(LoliConfig.replyFail.replace(replacement))
                cooldown[group.id] = System.currentTimeMillis() + LoliConfig.failCooldown * 1000
                receipt.recallIgnoreError()
                return@subscribeAlways
            }
            val url = when(LoliConfig.quality)
            {
                "FILE" -> loli.url
                "PREVIEW" -> loli.urlPreview
                else -> loli.urlSample
            }.replace(" ", "%20")
            replacement.putAll(mapOf(
                "id" to PlainText(loli.id.toString()),
                "url_preview" to PlainText(loli.urlPreview),
                "url_sample" to PlainText(loli.urlSample),
                "url_file" to PlainText(loli.url),
                "url" to PlainText(url),
                "tags" to PlainText(loli.tags),
                "rating" to PlainText(loli.rating),
                "pic" to PrepareUploadImage.url(group, url, LoliConfig.imageFailDownload
                ) { input ->
                    if (!LoliConfig.download) return@url input
                    val file = resolveDataFile(url.substringAfterLast('/'))
                    file.writeBytes(input.readBytes())
                    return@url FileInputStream(file)
                }
            ))
            group.sendMessage(LoliConfig.replySuccess.replace(replacement))
            receipt.recallIgnoreError()
        }
        logger.info { "Plugin loaded" }
    }
    fun searchLoli(loliList: List<Loli>): Loli? {
        return loliList
                // 为你的账号安全着想，请不要移除评级为 e 的图片过滤
                // 要涩涩就自己上源站看去
            .filter { it.rating != "e" }
            .filter { if (!LoliConfig.strictMode) it.rating != "q" else true}
            .randomOrNull()
    }

    fun reloadConfig() {
        LoliConfig.reload()
        LoliConfig.save()
        Lolibooru.baseUrl = LoliConfig.apiBaseUrl
    }
}
suspend fun MessageReceipt<Contact>.recallIgnoreError() {
    try {
        this.recall()
    }catch (_: Throwable) {}
}