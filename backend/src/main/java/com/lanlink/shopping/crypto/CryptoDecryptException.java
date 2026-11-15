package com.lanlink.shopping.crypto;

/**
 * 解密失败（严格模式）。
 *
 * <p>为什么单独定义：既有的 {@code CryptoService.decrypt} 出于"读旧坏数据不炸业务流程"的考虑返回 null，
 * 新代码（需要明确知道密钥配置错误，而不是把密文当明文/当空）走 {@code decryptStrict} 抛本异常。
 * 消息只描述失败原因，绝不携带明文或密钥，便于直接打日志与上报。</p>
 */
public class CryptoDecryptException extends RuntimeException {

    public CryptoDecryptException(String message) {
        super(message);
    }

    public CryptoDecryptException(String message, Throwable cause) {
        super(message, cause);
    }
}
