package miao.byusi.hp.common.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import miao.byusi.hp.common.protocol.HpMessageData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @author hxm
 */
public class HpMessageDecoder extends ByteToMessageDecoder {
    private static final Logger log = LoggerFactory.getLogger(HpMessageDecoder.class);

    /**
     * <pre>
     * 协议开始的标准head_data，int类型，占据4个字节.
     * 表示是否解压isUncompress，byte类型，占据1个字节. 1标识要解压 否则不解压
     * 表示数据的长度contentLength，int类型，占据4个字节.
     * </pre>
     */
    public final int BASE_LENGTH = 4 + 4;

    /**
     * 【安全修复 J7】单帧负载上限（4MB）。
     * <p>
     * 长度字段完全来自网络，原实现直接 {@code new byte[dataLength]} 且不设上限：
     * <ul>
     *   <li>超大值（如 0x7FFFFFFF）会让 ByteToMessageDecoder 一直累积字节，
     *       单个连接即可耗尽数 GB 堆内存（OOM 拒绝服务）；</li>
     *   <li>负值会直接抛 NegativeArraySizeException。</li>
     * </ul>
     * 该解码器监听节点的公开协议端口，任何远程调用方都能触发。
     * 超过上限或为负值一律记录日志并关闭连接，而不是分配内存。
     * <p>
     * 说明：Go 客户端（proxy-client-golang/Protol/Protol.go）自身的解码上限是 1MB，
     * 因此正常通信的帧远小于本上限；这里留出 4MB 余量。
     */
    public static final int MAX_FRAME_LENGTH = 4 * 1024 * 1024;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        // 可读长度必须大于基本长度
        if (in.readableBytes() < BASE_LENGTH) {
            return;
        }
        in.markReaderIndex();
        int header = in.readInt();
        if (header != 9999) {
            // 与历史行为保持一致：头不匹配时不回退读指针，按 4 字节向后滑动重新寻找帧起点。
            return;
        }
        // 魔数已消费，长度字段必须完整可读（BASE_LENGTH = 4 + 4 已保证）
        int dataLength = in.readInt();
        // 【安全修复 J7】长度必须在 [0, MAX_FRAME_LENGTH] 之内，否则拒绝并断开，
        // 绝不再按攻击者给出的长度累积/分配内存。
        if (dataLength < 0 || dataLength > MAX_FRAME_LENGTH) {
            log.warn("协议帧长度非法：{}（允许范围 0-{}），关闭连接 {}", dataLength, MAX_FRAME_LENGTH,
                    ctx.channel() == null ? "unknown" : ctx.channel().remoteAddress());
            in.resetReaderIndex();
            ctx.close();
            return;
        }
        if (in.readableBytes() < dataLength) {
            // 数据还没收全：回到帧起点，等待后续字节（此时累积量已受 MAX_FRAME_LENGTH 约束）
            in.resetReaderIndex();
            return;
        }
        byte[] data = new byte[dataLength];
        in.readBytes(data);
        out.add(HpMessageData.HpMessage.parseFrom(data));
    }

}
