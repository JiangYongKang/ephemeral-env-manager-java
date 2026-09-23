package com.github.highcumontoa.ephemeralenvmanagerjava.web.dto;

/** 创建请求体：ttlMillis 可空，缺省使用默认租期。 */
public class CreateRequest {
    private String name;
    private String owner;
    private Long ttlMillis;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public Long getTtlMillis() { return ttlMillis; }
    public void setTtlMillis(Long ttlMillis) { this.ttlMillis = ttlMillis; }
}
