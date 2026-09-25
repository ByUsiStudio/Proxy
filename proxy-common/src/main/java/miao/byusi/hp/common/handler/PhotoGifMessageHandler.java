package miao.byusi.hp.common.handler;

import miao.byusi.hp.common.message.Photo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * @author hxm
 */
public class PhotoGifMessageHandler extends PhotoMessageHandler {
    private static final Logger log = LoggerFactory.getLogger(PhotoGifMessageHandler.class);

    private final String username;
    private final String domain;

    public PhotoGifMessageHandler(String username, String domain) {
        this.username = username;
        this.domain = domain;
    }

    private final static List<Byte> hexStart = new ArrayList<Byte>() {
        {
            //47494638
            add((byte) Integer.parseInt("47", 16));
            add((byte) Integer.parseInt("49", 16));
            add((byte) Integer.parseInt("46", 16));
            add((byte) Integer.parseInt("38", 16));
        }
    };


    //00004945 4E44AE42 6082
    private final static List<Byte> hexEnd = new ArrayList<Byte>() {
        {
            add((byte) Integer.parseInt("10", 16));
            add((byte) Integer.parseInt("10", 16));
            add((byte) Integer.parseInt("00", 16));
            add((byte) Integer.parseInt("3B", 16));
        }
    };


    public static int isGifStart(byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == hexStart.get(0) && i + hexStart.size() <= bytes.length) {
                int j = i;
                for (Byte aByte : hexStart) {
                    if (bytes[j] == aByte) {
                        j++;
                    } else {
                        break;
                    }
                }
                if (j == i + hexStart.size()) {
                    return i;
                }
            }
        }
        return -1;
    }

    public static int isGifEnd(byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == hexEnd.get(0) && i + hexEnd.size() <= bytes.length) {
                int j = i;
                boolean flag = true;
                for (Byte aByte : hexEnd) {
                    if (bytes[j] == aByte) {
                        j++;
                    } else {
                        flag = false;
                    }
                }
                if (flag) {
                    return j;
                }
            }
        }
        return -1;
    }

    public byte[] change() {
        // 【安全修复 J8】缓冲已由父类用 ByteArrayOutputStream 实现（不再装箱，且有 4MB 硬上限）
        return takePhotoBytes();
    }

    public void add(byte[] bytes) {
        addPhotoBytes(bytes);
    }

    private boolean flag = false;


    @Override
    public boolean checkAndSavePhoto(byte[] bytes) {
        try {
            if (isPhotoDiscarded()) {
                // 上一张图已因超限被丢弃：复位状态，等待下一张
                flag = false;
                return false;
            }
            int pngStart = isGifStart(bytes);
            int pngEnd = isGifEnd(bytes);
            //一个数据包搞定的情况
            if (pngEnd >= 0 && pngStart >= 0) {
                byte[] data = new byte[pngEnd - pngStart];
                System.arraycopy(bytes, pngStart, data, 0, data.length);
                add(data);
                if (!isPhotoDiscarded()) {
                    save(new Photo(username,domain,Photo.PhotoType.GIF, change()));
                }
            } else {
                if (pngStart >= 0 && pngEnd == -1) {
                    flag = true;
                    byte[] data = new byte[bytes.length - pngStart];
                    System.arraycopy(bytes, pngStart, data, 0, data.length);
                    add(data);
                } else if (flag && pngStart == -1 && pngEnd == -1) {
                    add(bytes);
                    // 【安全修复 J8】超过上限时父类已丢弃缓冲，这里同步复位标志（原实现是 10MB 才清空）
                    if (isPhotoDiscarded()) {
                        flag = false;
                    }
                } else if (flag && pngStart == -1 && pngEnd >= 0) {
                    byte[] data = new byte[pngEnd];
                    System.arraycopy(bytes, 0, data, 0, data.length);
                    add(data);
                    flag = false;
                    if (!isPhotoDiscarded()) {
                        save(new Photo(username,domain,Photo.PhotoType.GIF, change()));
                    }
                }else {
                    return false;
                }
            }
        } catch (Exception e) {
            log.error("GIF图片处理异常", e);
            return false;
        } finally {
            // 一次调用结束后复位丢弃标记，避免影响下一张图片
            resetPhotoDiscarded();
        }
        return true;
    }
}
