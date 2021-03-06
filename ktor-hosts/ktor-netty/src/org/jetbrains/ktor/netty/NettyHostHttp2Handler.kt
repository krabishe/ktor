package org.jetbrains.ktor.netty

import io.netty.channel.*
import io.netty.handler.codec.http2.*
import io.netty.util.*
import io.netty.util.collection.*
import org.jetbrains.ktor.application.*
import org.jetbrains.ktor.host.*
import org.jetbrains.ktor.netty.http2.*
import org.jetbrains.ktor.response.*
import java.nio.channels.*

@ChannelHandler.Sharable
class NettyHostHttp2Handler(private val host: NettyApplicationHost, private val http2: Http2Connection) : ChannelInboundHandlerAdapter() {
    override fun channelRead(context: ChannelHandlerContext, message: Any?) {
        when (message) {
            is Http2HeadersFrame -> {
                startHttp2(context, message.streamId(), message.headers(), http2)
            }
            is Http2DataFrame -> {
                context.callByStreamId[message.streamId()]?.contentQueue?.push(message, message.isEndStream)
            }
            is Http2ResetFrame -> {
                context.callByStreamId[message.streamId()]?.contentQueue?.cancel(if (message.errorCode() == 0L) null else ClosedChannelException())
            }
            else -> context.fireChannelRead(message)
        }
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        ctx.pipeline().apply {
            addLast(host.callEventGroup, NettyApplicationCallHandler(host))
        }

        super.channelActive(ctx)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        ctx.pipeline().apply {
            remove(NettyApplicationCallHandler::class.java)
        }

        super.channelInactive(ctx)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        ctx.close()
    }

    private fun startHttp2(context: ChannelHandlerContext, streamId: Int, headers: Http2Headers, http2: Http2Connection) {
        val call = NettyHttp2ApplicationCall(host.application, context, streamId, headers, this, http2)
        context.callByStreamId[streamId] = call

        context.fireChannelRead(call)
    }

    fun startHttp2PushPromise(call: ApplicationCall, block: ResponsePushBuilder.() -> Unit, connection: Http2Connection, context: ChannelHandlerContext) {
        val builder = DefaultResponsePushBuilder(call)
        block(builder)

        val streamId = connection.local().incrementAndGetNextStreamId()
        val pushPromiseFrame = Http2PushPromiseFrame()
        pushPromiseFrame.promisedStreamId = streamId
        pushPromiseFrame.headers.apply {
            val pathAndQuery = builder.url.build().substringAfter("?", "").let { q ->
                if (q.isEmpty()) {
                    builder.url.encodedPath
                } else {
                    builder.url.encodedPath + "?" + q
                }
            }

            scheme(builder.url.protocol.name)
            method(builder.method.value)
            authority(builder.url.host + ":" + builder.url.port)
            path(pathAndQuery)
        }

        connection.local().createStream(streamId, false)

        context.writeAndFlush(pushPromiseFrame)

        startHttp2(context, streamId, pushPromiseFrame.headers, connection)
    }

    companion object {
        private val CallByStreamIdKey = AttributeKey.newInstance<IntObjectHashMap<NettyHttp2ApplicationCall>>("ktor.CallByStreamIdKey")

        private val ChannelHandlerContext.callByStreamId: IntObjectHashMap<NettyHttp2ApplicationCall>
            get() = channel().attr(CallByStreamIdKey).let { attr ->
                attr.get() ?: IntObjectHashMap<NettyHttp2ApplicationCall>().apply { attr.set(this) }
            }
    }
}