package com.lanlink.shopping.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
        log.warn("[bad-request] 参数校验失败已对外脱敏: {}", e.getMessage());
        return R.fail(400, "请求参数不合法");
    }

    /**
     * 未注册的接口路径与不存在的静态资源：这是路由问题，按 404 返回。
     *
     * <p>不单独处理时它会落进下面的 {@code Exception} 分支，对外报「服务器内部错误」——
     * 前端调了一个后端不存在的接口，看起来却像服务崩了，排障方向会被整个带偏。
     * 被探测的路径只写服务端日志，不回显给调用方。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public R<Void> handleNoResource(NoResourceFoundException e) {
        log.warn("[no-route] 未注册的请求路径: {}", e.getResourcePath());
        return R.fail(404, "请求的接口不存在");
    }

    @ExceptionHandler(Exception.class)
    public R<Void> handleOther(Exception e) {
        log.error("Unhandled server exception", e);
        return R.fail("服务器内部错误，请稍后重试");
    }
}
