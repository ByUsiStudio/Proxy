package miao.byusi.hp.common.handler;

import cn.hserver.core.queue.HServerQueue;
import miao.byusi.hp.common.message.Photo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class PhotoMessageHandler {
    private static final Logger log = LoggerFactory.getLogger(PhotoMessageHandler.class);

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
}
