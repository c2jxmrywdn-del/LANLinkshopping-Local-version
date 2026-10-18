package com.lanlink.shopping.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理。
 *
 * <p>数据安全规范：数据库异常（含 SQL 语句、表名/字段名、约束名等存储结构信息）
 * 一律在服务端记录完整日志，向前端仅返回统一脱敏文案，禁止泄漏存储层细节。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public R<Void> handleBusiness(BusinessException e) {
        return R.fail(e.getCode(), e.getMessage());
    }

    /**
     * 数据访问异常统一脱敏（需求 3）：
     * SQLException/约束冲突等消息含 SQL 与表/字段名，仅落服务端日志，前端拿到固定文案。
     */
    @ExceptionHandler(DataAccessException.class)
    public R<Void> handleDataAccess(DataAccessException e) {
        log.error("[db-error] 数据访问异常已脱敏拦截", e);
        return R.fail("数据服务暂时不可用，请稍后重试");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public R<Void> handleValid(Exception e) {
        BindException be = (e instanceof BindException) ? (BindException) e : null;
        FieldError fe = (be != null) ? be.getFieldError()
                : ((MethodArgumentNotValidException) e).getBindingResult().getFieldError();
        String msg = fe == null ? "参数校验失败" : fe.getDefaultMessage();
        return R.fail(400, msg);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public R<Void> handleIllegalArgument(IllegalArgumentException e) {
        return R.fail(400, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public R<Void> handleOther(Exception e) {
        log.error("Unhandled server exception", e);
        return R.fail("服务器内部错误，请稍后重试");
    }
}
