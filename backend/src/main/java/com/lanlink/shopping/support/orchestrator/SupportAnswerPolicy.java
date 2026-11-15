package com.lanlink.shopping.support.orchestrator;

import com.lanlink.shopping.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 客服答案来源安全策略与分流放行守卫。
 *
 * <p>网关会在响应 {@code lanlink.answer_generator} 里回报实际推理后端。
 * 格式与放行规则：
 * <ul>
 *   <li>{@code local:<backend>}：本地模型（llama.cpp / ollama / mock），无条件合法放行；</li>
 *   <li>{@code cloud:mimo-*} / {@code external:embedded} / {@code mimo:*}：受信任外部嵌入式大模型，
 *       仅在经三层漏斗分流判定为复杂问题（complexDiverted == true）时合法放行，严禁普通问题滥用外部计费通道；</li>
 *   <li>其他来源或空值：一律 502 拦截，防止未知渠道穿透。</li>
 * </ul>
 */
@Component
public class SupportAnswerPolicy {

    private static final Logger log = LoggerFactory.getLogger(SupportAnswerPolicy.class);
    private static final String LOCAL_PREFIX = "local:";

    private static final List<String> AUTHORIZED_EXTERNAL_PREFIXES = List.of(
            "cloud:mimo",
            "external:embedded",
            "external:mimo",
            "mimo:"
    );

    /**
     * 校验答案来源必须是本地模型（严格本地兼容方法）。
     *
     * @param generator 网关返回的 {@code lanlink.answer_generator}
     * @throws BusinessException 来源为空或非 local: 前缀（502 答案来源校验失败）
     */
    public void assertLocal(String generator) {
        assertAnswerAllowed(generator, false);
    }

    /**
     * 校验答案来源是否合法（支持复杂问题分流放行外部嵌入大模型）。
     *
     * @param generator 网关返回的 {@code lanlink.answer_generator}
     * @param complexDiverted 当前请求是否通过复杂问题分流认定
     * @throws BusinessException 502（非法来源/空来源）或 403（非复杂问题触发外部模型）
     */
    public void assertAnswerAllowed(String generator, boolean complexDiverted) {
        if (!StringUtils.hasText(generator)) {
            log.error("[support-policy] 答案来源为空");
            throw new BusinessException(502, "答案来源校验失败");
        }
        // 1. 本地模型通道一律放行
        if (generator.startsWith(LOCAL_PREFIX)) {
            return;
        }
        // 2. 受信任外部嵌入式大模型通道
        for (String prefix : AUTHORIZED_EXTERNAL_PREFIXES) {
            if (generator.startsWith(prefix)) {
                if (!complexDiverted) {
                    log.warn("[support-policy] 未经分流许可的外部模型尝试直接作答被拦截: generator={}", generator);
                    throw new BusinessException(403, "普通问题不允许触发外部生成");
                }
                log.info("[support-policy] 复杂问题合规外部模型嵌入解答放行: generator={}", generator);
                return;
            }
        }
        // 3. 未知或未授权来源
        log.error("[support-policy] 非法模型答案来源被拦截: generator={}", generator);
        throw new BusinessException(502, "答案来源校验失败");
    }

    /**
     * 判断给定生成器来源是否合法（无异常判定便捷方法）。
     *
     * @param generator 网关返回的 {@code lanlink.answer_generator}
     * @param complexDiverted 是否属于复杂问题分流
     * @return true 若来源受信任且符合放行条件
     */
    public boolean isAllowed(String generator, boolean complexDiverted) {
        try {
            assertAnswerAllowed(generator, complexDiverted);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }
}
