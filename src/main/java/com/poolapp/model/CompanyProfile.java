package com.poolapp.model;

import java.nio.file.Path;

public class CompanyProfile {
    private Long id;
    private String companyName;
    private String phone;
    private String address;
    private String username;
    private String password;
    private Path databasePath;

    public CompanyProfile() {
    }

    public CompanyProfile(Long id, String companyName, String phone, String address,
                          String username, String password, Path databasePath) {
        this.id = id;
        this.companyName = companyName;
        this.phone = phone;
        this.address = address;
        this.username = username;
        this.password = password;
        this.databasePath = databasePath;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Path getDatabasePath() {
        return databasePath;
    }

    public void setDatabasePath(Path databasePath) {
        this.databasePath = databasePath;
    }
}
