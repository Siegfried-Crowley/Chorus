package org.chorus.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {
    @NotBlank @Size(min = 2, max = 32) private String username;
    @Email @NotBlank private String email;
    @NotBlank @Size(min = 6, max = 128) private String password;
}
