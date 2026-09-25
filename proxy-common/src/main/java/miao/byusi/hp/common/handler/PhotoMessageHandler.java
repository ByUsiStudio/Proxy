package miao.byusi.hp.common.handler;

import cn.hserver.core.queue.HServerQueue;
import miao.byusi.hp.common.message.Photo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class PhotoMessageHandler {
    private static final Logger log = LoggerFactory.getLogger(PhotoMessageHandler.class);

    /**
     * 【安全修复 J8】单张图片的缓冲上限（4MB）。
     * <p>
     * 原实现用 {@code List<Byte>} 累积原始图片字节，仅在超过 10MB 时才 clear：
     * 装箱后的 {@code Byte} 每个对象约 16 字节 + 4/8 字节引用，
     * 10MB 图片实际占用 150-200MB 堆，而每条隧道连接都会创建若干实例，
     * 少量并发连接即可把堆撑爆。现在改为 {@link ByteArrayOutputStream}（无装箱），
     * 并把上限降到 4MB；超过上限立即丢弃已累积数据并停止缓冲（只记一次日志），
     * 不再无限增长。
     */
    protected static final int MAX_PHOTO_BYTES = 4 * 1024 * 1024;

    private final ByteArrayOutputStream photoBuffer = new ByteArrayOutputStream();
    private boolean photoDiscarded = false;
    private boolean photoOverflowLogged = false;

    /**
     * 【安全修复】原实现用非线程安全的 ArrayList 在 Netty 业务线程 add、后台线程 for 遍历 + remove，
     * 既会抛 ConcurrentModificationException，也可能丢照片；同时工作线程 catch 后直接抛
     * RuntimeException 退出循环，导致图片审核永久静默失效（漏检风险）。
     * 现改为有界并发队列 + poll 消费，并且任何异常都不会杀死工作线程。
     */
    private static final int MAX_QUEUE_SIZE = 2000;
    private static final Queue<Photo> PHOTOS = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger QUEUE_SIZE = new AtomicInteger();
    private static volatile boolean started = false;

    public PhotoMessageHandler() {
        startWorker();
    }

    private static synchronized void startWorker() {
        if (started) {
            return;
        }
        started = true;
        Thread async = new Thread(() -> {
            while (true) {
                try {
                    opPhoto();
                } catch (Throwable e) {
                    // 绝不因单次异常退出工作线程
                    log.error("图片处理线程异常，已忽略并继续运行", e);
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "photo-check-worker");
        async.setDaemon(true);
        async.start();
    }

    public abstract boolean checkAndSavePhoto(byte[] bytes);

    public void save(Photo photo) {
        if (photo == null) {
            return;
        }
        // 队列积压时丢弃，避免内存无上限增长
        if (QUEUE_SIZE.get() >= MAX_QUEUE_SIZE) {
            log.warn("图片审核队列已满，丢弃一条待审核图片");
            return;
        }
        QUEUE_SIZE.incrementAndGet();
        PHOTOS.offer(photo);
    }

    private static void opPhoto() {
        Photo photo;
        while ((photo = PHOTOS.poll()) != null) {
            QUEUE_SIZE.decrementAndGet();
            HServerQueue.sendQueue("PHOTO", photo);
        }
    }

    /**
     * 【安全修复 J8】追加图片字节；超过 {@link #MAX_PHOTO_BYTES} 时丢弃已缓冲数据并置位丢弃标记。
     */
    protected final void addPhotoBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || photoDiscarded) {
            return;
        }
        if (photoBuffer.size() + bytes.length > MAX_PHOTO_BYTES) {
            photoBuffer.reset();
            photoDiscarded = true;
            if (!photoOverflowLogged) {
                log.warn("图片数据超过 {} 字节上限，已丢弃该图片的缓冲数据", MAX_PHOTO_BYTES);
                photoOverflowLogged = true;
            }
            return;
        }
        photoBuffer.write(bytes, 0, bytes.length);
    }

    /**
     * 取出并清空已缓冲的图片字节（等价于原来的 change()）。
     */
    protected final byte[] takePhotoBytes() {
        byte[] data = photoBuffer.toByteArray();
        photoBuffer.reset();
        return data;
    }

    /**
     * 当前缓冲是否已因超过上限被丢弃（此时不应保存图片）。
     */
    protected final boolean isPhotoDiscarded() {
        return photoDiscarded;
    }

    /**
     * 一次 checkAndSavePhoto 结束后复位丢弃标记，等待下一张图片。
     */
    protected final void resetPhotoDiscarded() {
        photoDiscarded = false;
    }
}
