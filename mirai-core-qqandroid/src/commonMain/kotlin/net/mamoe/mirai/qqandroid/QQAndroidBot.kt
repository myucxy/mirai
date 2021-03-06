/*
 * Copyright 2020 Mamoe Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/mamoe/mirai/blob/master/LICENSE
 */

@file:Suppress("EXPERIMENTAL_API_USAGE")

package net.mamoe.mirai.qqandroid

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.async
import kotlinx.coroutines.io.ByteReadChannel
import kotlinx.coroutines.withContext
import kotlinx.serialization.UnstableDefault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import kotlinx.serialization.json.int
import net.mamoe.mirai.BotAccount
import net.mamoe.mirai.BotImpl
import net.mamoe.mirai.LowLevelAPI
import net.mamoe.mirai.contact.*
import net.mamoe.mirai.data.*
import net.mamoe.mirai.event.broadcast
import net.mamoe.mirai.event.events.MessageRecallEvent
import net.mamoe.mirai.message.MessageReceipt
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.qqandroid.contact.MemberInfoImpl
import net.mamoe.mirai.qqandroid.contact.QQImpl
import net.mamoe.mirai.qqandroid.contact.checkIsGroupImpl
import net.mamoe.mirai.qqandroid.io.serialization.toByteArray
import net.mamoe.mirai.qqandroid.message.OnlineFriendImageImpl
import net.mamoe.mirai.qqandroid.message.OnlineGroupImageImpl
import net.mamoe.mirai.qqandroid.network.QQAndroidBotNetworkHandler
import net.mamoe.mirai.qqandroid.network.QQAndroidClient
import net.mamoe.mirai.qqandroid.network.highway.HighwayHelper
import net.mamoe.mirai.qqandroid.network.protocol.data.proto.LongMsg
import net.mamoe.mirai.qqandroid.network.protocol.packet.chat.*
import net.mamoe.mirai.qqandroid.network.protocol.packet.list.FriendList
import net.mamoe.mirai.qqandroid.utils.toIpV4AddressString
import net.mamoe.mirai.utils.*
import net.mamoe.mirai.utils.io.encodeToString
import net.mamoe.mirai.utils.io.toReadPacket
import kotlin.collections.asSequence
import kotlin.coroutines.CoroutineContext
import kotlin.math.absoluteValue
import kotlin.random.Random

@OptIn(MiraiInternalAPI::class)
internal expect class QQAndroidBot constructor(
    context: Context,
    account: BotAccount,
    configuration: BotConfiguration
) : QQAndroidBotBase

@OptIn(MiraiInternalAPI::class, MiraiExperimentalAPI::class)
internal abstract class QQAndroidBotBase constructor(
    context: Context,
    account: BotAccount,
    configuration: BotConfiguration
) : BotImpl<QQAndroidBotNetworkHandler>(context, account, configuration) {
    val client: QQAndroidClient =
        QQAndroidClient(
            context,
            account,
            bot = @Suppress("LeakingThis") this as QQAndroidBot,
            device = configuration.deviceInfo?.invoke(context) ?: SystemDeviceInfo(context)
        )
    internal var firstLoginSucceed: Boolean = false
    override val uin: Long get() = client.uin

    companion object {
        @OptIn(UnstableDefault::class)
        val json = Json(JsonConfiguration(ignoreUnknownKeys = true, encodeDefaults = true))
    }

    override val friends: ContactList<QQ> = ContactList(LockFreeLinkedList())

    override val selfQQ: QQ by lazy {
        @OptIn(LowLevelAPI::class)
        _lowLevelNewQQ(object : FriendInfo {
            override val uin: Long get() = this@QQAndroidBotBase.uin
            override val nick: String get() = this@QQAndroidBotBase.nick
        })
    }

    @LowLevelAPI
    override fun _lowLevelNewQQ(friendInfo: FriendInfo): QQ {
        return QQImpl(
            this as QQAndroidBot,
            coroutineContext + CoroutineName("QQ(${friendInfo.uin}"),
            friendInfo.uin,
            friendInfo
        )
    }

    override fun createNetworkHandler(coroutineContext: CoroutineContext): QQAndroidBotNetworkHandler {
        return QQAndroidBotNetworkHandler(this as QQAndroidBot)
    }

    override val groups: ContactList<Group> = ContactList(LockFreeLinkedList())

    // internally visible only
    fun getGroupByUin(uin: Long): Group {
        return getGroupByUinOrNull(uin) ?: throw NoSuchElementException("Group $uin not found")
    }

    fun getGroupByUinOrNull(uin: Long): Group? {
        return groups.asSequence().firstOrNull { it.checkIsGroupImpl(); it.uin == uin }
    }

    @OptIn(LowLevelAPI::class)
    override suspend fun _lowLevelQueryGroupList(): Sequence<Long> {
        return network.run {
            FriendList.GetTroopListSimplify(bot.client)
                .sendAndExpect<FriendList.GetTroopListSimplify.Response>(retry = 2)
        }.groups.asSequence().map { it.groupUin.shl(32) and it.groupCode }
    }

    @OptIn(LowLevelAPI::class)
    override suspend fun _lowLevelQueryGroupInfo(groupCode: Long): GroupInfo = network.run {
        TroopManagement.GetGroupInfo(
            client = bot.client,
            groupCode = groupCode
        ).sendAndExpect<GroupInfoImpl>(retry = 2)
    }

    @OptIn(LowLevelAPI::class)
    override suspend fun _lowLevelQueryGroupMemberList(
        groupUin: Long,
        groupCode: Long,
        ownerId: Long
    ): Sequence<MemberInfo> =
        network.run {
            var nextUin = 0L
            var sequence = sequenceOf<MemberInfoImpl>()
            while (true) {
                val data = FriendList.GetTroopMemberList(
                    client = bot.client,
                    targetGroupUin = groupUin,
                    targetGroupCode = groupCode,
                    nextUin = nextUin
                ).sendAndExpect<FriendList.GetTroopMemberList.Response>(timeoutMillis = 3000)
                sequence += data.members.asSequence().map { troopMemberInfo ->
                    MemberInfoImpl(troopMemberInfo, ownerId)
                }
                nextUin = data.nextUin
                if (nextUin == 0L) {
                    break
                }
            }
            return sequence
        }

    override suspend fun addFriend(id: Long, message: String?, remark: String?): AddFriendResult {
        TODO("not implemented")
    }

    @ExperimentalMessageSource
    override suspend fun recall(source: MessageSource) {
        if (source.senderId != uin && source.groupId != 0L) {
            getGroup(source.groupId).checkBotPermissionOperator()
        }

        // println(source._miraiContentToString())
        source.ensureSequenceIdAvailable()

        network.run {
            val response: PbMessageSvc.PbMsgWithDraw.Response =
                if (source.groupId == 0L) {
                    PbMessageSvc.PbMsgWithDraw.Friend(
                        bot.client,
                        source.senderId,
                        source.sequenceId,
                        source.messageRandom,
                        source.time
                    ).sendAndExpect()
                } else {
                    MessageRecallEvent.GroupRecall(
                        bot,
                        source.senderId,
                        source.id,
                        source.time.toInt(),
                        null,
                        getGroup(source.groupId)
                    ).broadcast()
                    PbMessageSvc.PbMsgWithDraw.Group(
                        bot.client,
                        source.groupId,
                        source.sequenceId,
                        source.messageRandom
                    ).sendAndExpect()
                }

            check(response is PbMessageSvc.PbMsgWithDraw.Response.Success) { "Failed to recall message #${source.id}: $response" }
        }
    }

    @OptIn(LowLevelAPI::class)
    override suspend fun _lowLevelRecallFriendMessage(friendId: Long, messageId: Long, time: Long) {
        network.run {
            val response: PbMessageSvc.PbMsgWithDraw.Response =
                PbMessageSvc.PbMsgWithDraw.Friend(client, friendId, (messageId shr 32).toInt(), messageId.toInt(), time)
                    .sendAndExpect()

            check(response is PbMessageSvc.PbMsgWithDraw.Response.Success) { "Failed to recall message #${messageId}: $response" }
        }
    }

    @LowLevelAPI
    override suspend fun _lowLevelRecallGroupMessage(groupId: Long, messageId: Long) {
        network.run {
            val response: PbMessageSvc.PbMsgWithDraw.Response =
                PbMessageSvc.PbMsgWithDraw.Group(client, groupId, (messageId shr 32).toInt(), messageId.toInt())
                    .sendAndExpect()

            check(response is PbMessageSvc.PbMsgWithDraw.Response.Success) { "Failed to recall message #${messageId}: $response" }
        }
    }


    @LowLevelAPI
    @MiraiExperimentalAPI
    override suspend fun _lowLevelGetAnnouncements(groupId: Long, page: Int, amount: Int): GroupAnnouncementList {
        val data = network.async {
            MiraiPlatformUtils.Http.post<String> {
                url("https://web.qun.qq.com/cgi-bin/announce/list_announce")
                body = MultiPartFormDataContent(formData {
                    append("qid", groupId)
                    append("bkn", bkn)
                    append("ft", 23)  //好像是一个用来识别应用的参数
                    append("s", if (page == 1) 0 else -(page * amount + 1))  // 第一页这里的参数应该是-1
                    append("n", amount)
                    append("ni", if (page == 1) 1 else 0)
                    append("format", "json")
                })
                headers {
                    append(
                        "cookie",
                        "uin=o${selfQQ.id}; skey=${client.wLoginSigInfo.sKey.data.encodeToString()}; p_uin=o${selfQQ.id};"
                    )
                }
            }
        }

        val rep = data.await()
//        bot.network.logger.error(rep)
        return json.parse(GroupAnnouncementList.serializer(), rep)
    }

    @LowLevelAPI
    @MiraiExperimentalAPI
    override suspend fun _lowLevelSendAnnouncement(groupId: Long, announcement: GroupAnnouncement): String {
        val rep = withContext(network.coroutineContext) {
            MiraiPlatformUtils.Http.post<String> {
                url("https://web.qun.qq.com/cgi-bin/announce/add_qun_notice")
                body = MultiPartFormDataContent(formData {
                    append("qid", groupId)
                    append("bkn", bkn)
                    append("text", announcement.msg.text)
                    append("pinned", announcement.pinned)
                    append(
                        "settings",
                        json.stringify(
                            GroupAnnouncementSettings.serializer(),
                            announcement.settings ?: GroupAnnouncementSettings()
                        )
                    )
                    append("format", "json")
                })
                headers {
                    append(
                        "cookie",
                        "uin=o${selfQQ.id};" +
                                " skey=${client.wLoginSigInfo.sKey.data.encodeToString()};" +
                                " p_uin=o${selfQQ.id};" +
                                " p_skey=${client.wLoginSigInfo.psKeyMap["qun.qq.com"]?.data?.encodeToString()}; "
                    )
                }
            }
        }
        val jsonObj = json.parseJson(rep)
        return jsonObj.jsonObject["new_fid"]?.primitive?.content
            ?: throw throw IllegalStateException("Send Announcement fail group:$groupId msg:${jsonObj.jsonObject["em"]} content:${announcement.msg.text}")
    }

    @LowLevelAPI
    @MiraiExperimentalAPI
    override suspend fun _lowLevelDeleteAnnouncement(groupId: Long, fid: String) {
        val data = withContext(network.coroutineContext) {
            MiraiPlatformUtils.Http.post<String> {
                url("https://web.qun.qq.com/cgi-bin/announce/del_feed")
                body = MultiPartFormDataContent(formData {
                    append("qid", groupId)
                    append("bkn", bkn)
                    append("fid", fid)
                    append("format", "json")
                })
                headers {
                    append(
                        "cookie",
                        "uin=o${selfQQ.id};" +
                                " skey=${client.wLoginSigInfo.sKey.data.encodeToString()};" +
                                " p_uin=o${selfQQ.id};" +
                                " p_skey=${client.wLoginSigInfo.psKeyMap["qun.qq.com"]?.data?.encodeToString()}; "
                    )
                }
            }
        }
        val jsonObj = json.parseJson(data)
        if (jsonObj.jsonObject["ec"]?.int ?: 1 != 0) {
            throw throw IllegalStateException("delete Announcement fail group:$groupId msg:${jsonObj.jsonObject["em"]} fid:$fid")
        }
    }

    @LowLevelAPI
    @MiraiExperimentalAPI
    override suspend fun _lowLevelGetAnnouncement(groupId: Long, fid: String): GroupAnnouncement {
        val data = network.async {
            HttpClient().post<String> {
                url("https://web.qun.qq.com/cgi-bin/announce/get_feed")
                body = MultiPartFormDataContent(formData {
                    append("qid", groupId)
                    append("bkn", bkn)
                    append("fid", fid)
                    append("format", "json")
                })
                headers {
                    append(
                        "cookie",
                        "uin=o${selfQQ.id}; skey=${client.wLoginSigInfo.sKey.data.encodeToString()}; p_uin=o${selfQQ.id};"
                    )
                }
            }
        }

        val rep = data.await()
//        bot.network.logger.error(rep)
        return json.parse(GroupAnnouncement.serializer(), rep)

    }

    @LowLevelAPI
    @MiraiExperimentalAPI
    override suspend fun _lowLevelGetGroupActiveData(groupId: Long): GroupActiveData {
        val data = network.async {
            HttpClient().get<String> {
                url("https://qqweb.qq.com/c/activedata/get_mygroup_data")
                parameter("bkn", bkn)
                parameter("gc", groupId)

                headers {
                    append(
                        "cookie",
                        "uin=o${selfQQ.id}; skey=${client.wLoginSigInfo.sKey.data.encodeToString()}; p_uin=o${selfQQ.id};"
                    )
                }
            }
        }
        val rep = data.await()
        return json.parse(GroupActiveData.serializer(), rep)
    }

    @LowLevelAPI
    @MiraiExperimentalAPI
    internal suspend fun _lowLevelSendLongGroupMessage(groupCode: Long, message: MessageChain): MessageReceipt<Group> {
        val group = getGroup(groupCode)

        val time = currentTimeSeconds

        network.run {
            val data = message.calculateValidationDataForGroup(
                sequenceId = client.atomicNextMessageSequenceId(),
                time = time.toInt(),
                random = Random.nextInt().absoluteValue.toUInt(),
                groupCode,
                group.botAsMember.nameCardOrNick
            )

            val response =
                MultiMsg.ApplyUp.createForGroupLongMessage(
                    client = this@QQAndroidBotBase.client,
                    messageData = data,
                    dstUin = Group.calculateGroupUinByGroupCode(groupCode)
                ).sendAndExpect<MultiMsg.ApplyUp.Response>()

            val resId: String
            when (response) {
                is MultiMsg.ApplyUp.Response.MessageTooLarge ->
                    error("message is too large")
                is MultiMsg.ApplyUp.Response.RequireUpload -> {
                    resId = response.proto.msgResid

                    val body = LongMsg.ReqBody(
                        subcmd = 1,
                        platformType = 9,
                        termType = 5,
                        msgUpReq = listOf(
                            LongMsg.MsgUpReq(
                                msgType = 3, // group
                                dstUin = Group.calculateGroupUinByGroupCode(groupCode),
                                msgId = 0,
                                msgUkey = response.proto.msgUkey,
                                needCache = 0,
                                storeType = 2,
                                msgContent = data.data
                            )
                        )
                    ).toByteArray(LongMsg.ReqBody.serializer())

                    HighwayHelper.uploadImage(
                        client,
                        serverIp = response.proto.uint32UpIp!!.first().toIpV4AddressString(),
                        serverPort = response.proto.uint32UpPort!!.first(),
                        ticket = response.proto.msgSig, // 104
                        imageInput = body.toReadPacket(),
                        inputSize = body.size,
                        fileMd5 = MiraiPlatformUtils.md5(body),
                        commandId = 27 // long msg
                    )
                }
            }

            return group.sendMessage(
                RichMessage.longMessage(
                    brief = message.joinToString(limit = 27){
                        when(it){
                            is PlainText -> it.stringValue
                            is At -> it.display
                            is AtAll -> it.display
                            is Image -> "[图片]"
                            is Face -> "[表情]"
                            else -> ""
                        }
                    },
                    resId = resId,
                    timeSeconds = time
                )
            )
        }
    }

    override suspend fun queryImageUrl(image: Image): String = when (image) {
        is OnlineFriendImageImpl -> image.originUrl
        is OnlineGroupImageImpl -> image.originUrl
        is OfflineGroupImage -> {
            TODO("暂不支持获取离线图片链接")
        }
        is OfflineFriendImage -> {
            TODO("暂不支持获取离线图片链接")
        }
        else -> error("unsupported image class: ${image::class.simpleName}")
    }

    override suspend fun openChannel(image: Image): ByteReadChannel {
        return MiraiPlatformUtils.Http.get<HttpResponse>(queryImageUrl(image)).content.toKotlinByteReadChannel()
    }

    /**
     * 获取 获取群公告 所需的 bkn 参数
     * */
    private val bkn: Int
        get() = client.wLoginSigInfo.sKey.data
            .fold(5381) { acc: Int, b: Byte -> acc + acc.shl(5) + b.toInt() }
}

@Suppress("DEPRECATION")
@OptIn(MiraiInternalAPI::class)
internal expect fun io.ktor.utils.io.ByteReadChannel.toKotlinByteReadChannel(): ByteReadChannel