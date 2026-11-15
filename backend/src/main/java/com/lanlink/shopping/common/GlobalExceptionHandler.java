package com.lanlink.shopping.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
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
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public R<Void> handleDataAccess(DataAccessException e) {
        log.error("[db-error] 数据访问异常已脱敏拦截", e);
        return R.fail(503, "数据服务暂时不可用，请稍后重试");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleValid(Exception e) {
        BindException be = (e instanceof BindException) ? (BindException) e : null;
        FieldError fe = (be != null) ? be.getFieldError()
                : ((MethodArgumentNotValidException) e).getBindingResult().getFieldError();
        String msg = fe == null ? "参数校验失败" : fe.getDefaultMessage();
        return R.fail(400, msg);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("[bad-request] 参数校验失败已对外脱敏: {}", e.getMessage());
        return R.fail(400, "请求参数不合法");
    }

    /**
     * 缺少必填查询参数：这是调用方参数问题（400），不是服务端故障（500）。
     * 之前未单独处理会落进 handleOther，前端显示"服务异常，请稍后重试"，误导排障。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("[bad-request] 缺少必填参数: {}", e.getParameterName());
        return R.fail(400, "缺少必要参数：" + e.getParameterName());
    }

    /** 参数类型不匹配（如 id 传了非数字）：400。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("[bad-request] 参数类型不匹配: {}={}", e.getName(), e.getValue());
        return R.fail(400, "参数格式不正确：" + e.getName());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public R<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return R.fail(405, "请求方法不支持");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public R<Void> handleMediaType(HttpMediaTypeNotSupportedException e) {
        return R.fail(415, "请求内容类型不支持");
    }

    /**
     * 未注册的接口路径与不存在的静态资源：这是路由问题，按 404 返回。
     *
     * <p>不单独处理时它会落进下面的 {@code Exception} 分支，对外报「服务器内部错误」——
     * 前端调了一个后端不存在的接口，看起来却像服务崩了，排障方向会被整个带偏。
     * 被探测的路径只写服务端日志，不回显给调用方。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public R<Void> handleNoResource(NoResourceFoundException e) {
        log.warn("[no-route] 未注册的请求路径: {}", e.getResourcePath());
        return R.fail(404, "请求的接口不存在");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleOther(Exception e) {
        log.error("Unhandled server exception", e);
        return R.fail(500, "服务器内部错误，请稍后重试");
    }
}
