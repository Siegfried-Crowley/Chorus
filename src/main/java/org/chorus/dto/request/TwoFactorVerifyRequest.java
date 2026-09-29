package org.chorus.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TwoFactorVerifyRequest {
    @NotBlank private String mfaToken;
    @NotBlank private String code;
}
