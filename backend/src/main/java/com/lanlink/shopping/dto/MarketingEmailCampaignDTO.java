package com.lanlink.shopping.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Single campaign batch for opted-in recipients only.
 * The caller must supply a working unsubscribe endpoint and confirm consent.
 */
@Data
public class MarketingEmailCampaignDTO {
    @NotEmpty
    @Size(max = 20, message = "单次营销邮件最多 20 位收件人")
    private List<@NotBlank @Email String> recipients;

    @NotBlank
    @Size(max = 998)
    private String subject;

    @NotBlank
    @Size(max = 500000)
    private String text;

    @NotBlank
    @Pattern(regexp = "^https://\\S+$", message = "退订地址必须使用 HTTPS")
    @Size(max = 2048)
    private String unsubscribeUrl;

    @NotBlank
    @Size(min = 8, max = 128)
    private String idempotencyKey;

    @AssertTrue(message = "仅可向已明确订阅营销邮件的收件人发送")
    private boolean consentConfirmed;
}
