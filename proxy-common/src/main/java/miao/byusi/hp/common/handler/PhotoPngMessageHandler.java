package miao.byusi.hp.common.handler;


import miao.byusi.hp.common.message.Photo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * @author hxm
 */
public class PhotoPngMessageHandler extends PhotoMessageHandler{
    private static final Logger log = LoggerFactory.getLogger(PhotoPngMessageHandler.class);

    private final String username;
    private final String domain;

    public PhotoPngMessageHandler(String username, String domain) {
        this.username = username;
        this.domain = domain;
    }

    //89504E47 0D0A1A0A 0000000D 49484452
    private final static List<Byte> hexStart = new ArrayList<Byte>() {
        {
            add((byte) Integer.parseInt("89", 16));
            add((byte) Integer.parseInt("50", 16));
            add((byte) Integer.parseInt("4E", 16));
            add((byte) Integer.parseInt("47", 16));

            add((byte) Integer.parseInt("0D", 16));
            add((byte) Integer.parseInt("0A", 16));
            add((byte) Integer.parseInt("1A", 16));
            add((byte) Integer.parseInt("0A", 16));

            add((byte) Integer.parseInt("00", 16));
            add((byte) Integer.parseInt("00", 16));
            add((byte) Integer.parseInt("00", 16));
            add((byte) Integer.parseInt("0D", 16));

            add((byte) Integer.parseInt("49", 16));
            add((byte) Integer.parseInt("48", 16));
            add((byte) Integer.parseInt("44", 16));
            add((byte) Integer.parseInt("52", 16));
        }
    };


    //00004945 4E44AE42 6082
    private final static List<Byte> hexEnd = new ArrayList<Byte>() {
        {
            add((byte) Integer.parseInt("49", 16));
            add((byte) Integer.parseInt("45", 16));

            add((byte) Integer.parseInt("4E", 16));
            add((byte) Integer.parseInt("44", 16));
            add((byte) Integer.parseInt("AE", 16));
            add((byte) Integer.parseInt("42", 16));

            add((byte) Integer.parseInt("60", 16));
            add((byte) Integer.parseInt("82", 16));
        }
    };


    public static int isPngStart(byte[] bytes) {
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

    public static int isPngEnd(byte[] bytes) {
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
            int pngStart = isPngStart(bytes);
            int pngEnd = isPngEnd(bytes);
            //一个数据包搞定的情况
            if (pngEnd >= 0 && pngStart >= 0) {
                byte[] data = new byte[pngEnd - pngStart];
                System.arraycopy(bytes, pngStart, data, 0, data.length);
                add(data);
                if (!isPhotoDiscarded()) {
                    save(new Photo(username,domain,Photo.PhotoType.PNG, change()));
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
                        save(new Photo(username,domain,Photo.PhotoType.PNG, change()));
                    }
                }else {
                    return false;
                }
            }
        } catch (Exception e) {
            log.error("PNG图片处理异常", e);
            return false;
        } finally {
            // 一次调用结束后复位丢弃标记，避免影响下一张图片
            resetPhotoDiscarded();
        }
        return true;
    }
}
