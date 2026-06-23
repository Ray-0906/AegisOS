package com.aegisos.api.dto.membership;

public class MembershipResponse {
    public String status;
    public String message;

    public MembershipResponse() {
        this.status = null;
        this.message = null;
    }

    public MembershipResponse(String status, String message) {
        this.status = status;
        this.message = message;
    }
}
