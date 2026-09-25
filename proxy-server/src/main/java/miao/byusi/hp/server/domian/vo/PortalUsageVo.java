package miao.byusi.hp.server.domian.vo;

import java.util.ArrayList;
import java.util.List;

/**
 * 门户「我的用量」页面的数据载体。
 * <p>
 * 统计口径（必须如实展示给用户，模板里也写了同样的提示）：
 * 数据来自 {@code sys_statistics} 的<b>采样汇总</b>（服务端单次最多读取
 * {@code StatisticsServiceImpl.SAMPLE_LIMIT}=20000 条，按时间倒序），
 * 因此「累计」是样本内的累计，「本月」是本月初至今的样本合计；
 * 记录量超过采样上限时数值会偏小，不会偏大。
 */
public class PortalUsageVo {

    /**
     * 单日流量点（用于折线图，缺失的日期由服务端补 0，保证 X 轴连续）。
     */
    public static class DayPoint {

        private String day;
        private long receive;
        private long send;
        private long connectNum;
        private long packNum;

        public DayPoint() {
        }

        public DayPoint(String day) {
            this.day = day;
        }

        public String getDay() {
            return day == null ? "" : day;
        }

        public void setDay(String day) {
            this.day = day;
        }

        public long getReceive() {
            return receive;
        }

        public void setReceive(long receive) {
            this.receive = receive;
        }

        public long getSend() {
            return send;
        }

        public void setSend(long send) {
            this.send = send;
        }

        public long getConnectNum() {
            return connectNum;
        }

        public void setConnectNum(long connectNum) {
            this.connectNum = connectNum;
        }

        public long getPackNum() {
            return packNum;
        }

        public void setPackNum(long packNum) {
            this.packNum = packNum;
        }
    }

    /**
     * 单端口流量行。
     */
    public static class PortRow {

        private int port;
        private long receive;
        private long send;
        private long connectNum;
        private long packNum;

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public long getReceive() {
            return receive;
        }

        public void setReceive(long receive) {
            this.receive = receive;
        }

        public long getSend() {
            return send;
        }

        public void setSend(long send) {
            this.send = send;
        }

        public long getConnectNum() {
            return connectNum;
        }

        public void setConnectNum(long connectNum) {
            this.connectNum = connectNum;
        }

        public long getPackNum() {
            return packNum;
        }

        public void setPackNum(long packNum) {
            this.packNum = packNum;
        }
    }

    /** 会话账号（由服务端注入，绝不来自请求参数，仅用于页面显示） */
    private String username;

    /** 图表展示的天数（已由服务端夹紧到 1..30） */
    private int days = 14;

    /** 当前月份标签，形如 2025-06 */
    private String monthLabel = "";

    /** 采样上限（用于页面提示，与 StatisticsServiceImpl 保持一致） */
    private int sampleLimit = 20000;

    private long totalReceive;
    private long totalSend;
    private long totalConnect;
    private long totalPack;
    private int sampleCount;

    private long monthReceive;
    private long monthSend;
    private long monthConnect;
    private long monthPack;
    private int monthRowCount;

    private List<DayPoint> byDay = new ArrayList<>();
    private List<PortRow> byPort = new ArrayList<>();

    public String getUsername() {
        return username == null ? "" : username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public int getDays() {
        return days;
    }

    public void setDays(int days) {
        this.days = days;
    }

    public String getMonthLabel() {
        return monthLabel == null ? "" : monthLabel;
    }

    public void setMonthLabel(String monthLabel) {
        this.monthLabel = monthLabel;
    }

    public int getSampleLimit() {
        return sampleLimit;
    }

    public void setSampleLimit(int sampleLimit) {
        this.sampleLimit = sampleLimit;
    }

    public long getTotalReceive() {
        return totalReceive;
    }

    public void setTotalReceive(long totalReceive) {
        this.totalReceive = totalReceive;
    }

    public long getTotalSend() {
        return totalSend;
    }

    public void setTotalSend(long totalSend) {
        this.totalSend = totalSend;
    }

    public long getTotalConnect() {
        return totalConnect;
    }

    public void setTotalConnect(long totalConnect) {
        this.totalConnect = totalConnect;
    }

    public long getTotalPack() {
        return totalPack;
    }

    public void setTotalPack(long totalPack) {
        this.totalPack = totalPack;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    public void setSampleCount(int sampleCount) {
        this.sampleCount = sampleCount;
    }

    public long getMonthReceive() {
        return monthReceive;
    }

    public void setMonthReceive(long monthReceive) {
        this.monthReceive = monthReceive;
    }

    public long getMonthSend() {
        return monthSend;
    }

    public void setMonthSend(long monthSend) {
        this.monthSend = monthSend;
    }

    public long getMonthConnect() {
        return monthConnect;
    }

    public void setMonthConnect(long monthConnect) {
        this.monthConnect = monthConnect;
    }

    public long getMonthPack() {
        return monthPack;
    }

    public void setMonthPack(long monthPack) {
        this.monthPack = monthPack;
    }

    public int getMonthRowCount() {
        return monthRowCount;
    }

    public void setMonthRowCount(int monthRowCount) {
        this.monthRowCount = monthRowCount;
    }

    public List<DayPoint> getByDay() {
        return byDay;
    }

    public void setByDay(List<DayPoint> byDay) {
        this.byDay = byDay;
    }

    public List<PortRow> getByPort() {
        return byPort;
    }

    public void setByPort(List<PortRow> byPort) {
        this.byPort = byPort;
    }
}
