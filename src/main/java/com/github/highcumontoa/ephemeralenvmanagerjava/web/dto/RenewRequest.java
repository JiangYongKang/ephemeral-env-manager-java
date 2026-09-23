package com.github.highcumontoa.ephemeralenvmanagerjava.web.dto;

/** 续租请求体：extendMillis 可空，缺省使用默认租期。 */
public class RenewRequest {
    private String owner;
    private Long extendMillis;

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public Long getExtendMillis() { return extendMillis; }
    public void setExtendMillis(Long extendMillis) { this.extendMillis = extendMillis; }
}
