package miao.byusi.hp.server.service;

import miao.byusi.hp.server.domian.entity.TemplateEntity;
import org.beetl.sql.core.page.PageResult;

import java.util.List;
import java.util.Map;

/**
 * 批量隧道模板与一键部署。
 * <p>
 * 安全与边界约定：
 * <ul>
 *   <li>模板条目（items）<b>永远不含口令</b>：口令是账号级凭据，由客户端 bootstrap 时
 *       用当前登录凭据补齐；模板一旦带口令，一次导出就等于批量凭据泄露。</li>
 *   <li>条目字段全部通过 {@code SafeInputUtil} 白名单校验（type/host:port/domain/port），
 *       整体解析走 Jackson，绝不手工拼 JSON。</li>
 *   <li>下发时同时受<b>配额</b>（QuotaService）与<b>既有硬上限</b>
 *       （{@code ConstConfig.PROXY_SIZE}，由 ConfigService#importBatch 兜底）约束。</li>
 * </ul>
 */
public interface TemplateService {

    /** 后台分页列表（关键字模糊匹配名称或描述）。 */
    PageResult<TemplateEntity> list(Integer page, Integer pageSize, String keyword);

    /** 取全量（受 limit 限制），用于导出。 */
    List<TemplateEntity> listAll(int limit);

    TemplateEntity getById(String id);

    /**
     * 新增/更新模板。
     * <p>调用方必须先通过 {@link #normalizeItems(String)} 校验条目。
     *
     * @return 落库后的实体（含生成的 id/时间），失败返回 null
     */
    TemplateEntity save(TemplateEntity template);

    boolean remove(String id);

    int removeBatch(List<String> ids);

    /**
     * 校验并规范化条目 JSON。
     *
     * @param itemsJson JSON 数组文本，或 {@code {"items":[...]}} 形式的对象
     * @return {@code {ok: Boolean, items: String(规范化后的 JSON 数组), count: Integer,
     *         failures: List<String>}}；ok=false 时 items 为 null，调用方应整体拒绝保存
     */
    Map<String, Object> normalizeItems(String itemsJson);

    /**
     * 计算各模板的条目数（供后台列表展示，避免把 JSON 解析逻辑塞进控制器/模板）。
     *
     * @return key = 模板 id，value = 可解析出的条目数（解析失败为 0）
     */
    Map<String, Integer> itemCounts(List<TemplateEntity> templates);

    /**
     * 一键下发：把模板条目作为自动穿透配置写入指定账号。
     * <p>
     * <b>deviceId 的取值决策（重要）：</b>{@code sys_config.device_id} 是
     * {@code NOT NULL}，而模板条目里没有设备维度。因此：
     * <ul>
     *   <li>下发表单可显式指定 deviceId（推荐：这样客户端的
     *       {@code /config/listDevice} 引导流程才能取到这条配置）；</li>
     *   <li>未指定时使用文档化的占位值 {@code template}。此时配置仍然属于该账号、
     *       会出现在后台「自动穿透」列表与该账号的 {@code /config/list} 结果里，
     *       <b>但不会被某一台设备开机自动加载</b>（引导接口按设备 ID 过滤）。</li>
     * </ul>
     *
     * @param templateId 模板 id
     * @param usernames  目标账号名列表（调用方已按上限截断）
     * @param deviceId   可为空；为空时使用占位值 {@code template}
     * @return {@code {applied, created, skipped, failures:List<String>, items:Integer}}
     */
    Map<String, Object> applyTo(String templateId, List<String> usernames, String deviceId);
}
