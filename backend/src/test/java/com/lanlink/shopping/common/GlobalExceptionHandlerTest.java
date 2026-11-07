package com.lanlink.shopping.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 未注册路径的错误语义：必须是 404，而不是伪装成 500 的基础设施故障。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private R<Void> dispatch(Exception e) throws Exception {
        ExceptionHandlerMethodResolver resolver =
                new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);
        Method m = resolver.resolveMethod(e);
        assertNotNull(m, "异常必须有可用的处理器");
        return (R<Void>) m.invoke(handler, e);
    }

    @Test
    void unmappedApiPathReturns404() throws Exception {
        NoResourceFoundException e =
                new NoResourceFoundException(HttpMethod.GET, "admin/tech-service/contracts-not-mapped");
        R<Void> r = dispatch(e);
        assertEquals(404, r.getCode(),
                "接口没注册是路由问题，报 500 会把「前端调了后端没有的接口」伪装成服务器崩了");
    }

    @Test
    void unmappedPathMessageDoesNotLeakInternalRoute() throws Exception {
        R<Void> r = dispatch(new NoResourceFoundException(HttpMethod.GET, "admin/tech-service/secret-path"));
        assertNotNull(r.getMessage());
        assertFalse(r.getMessage().contains("secret-path"), "对外文案不应回显被探测的路径");
        assertFalse(r.getMessage().contains("static resource"), "不暴露内部静态资源处理机制");
    }

    @Test
    void realServerErrorsStay500() throws Exception {
        R<Void> r = dispatch(new IllegalStateException("db connection lost"));
        assertEquals(500, r.getCode(), "真正的服务端异常不能被顺手降级成 404");
    }
}
