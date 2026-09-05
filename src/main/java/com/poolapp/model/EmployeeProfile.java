package com.poolapp.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class EmployeeProfile {
    private Long employeeId;
    private long companyId;
    private String companyName;
    private Path companyDatabasePath;
    private String firstName;
    private String lastName;
    private String phone;
    private String email;
    private String username;
    private String password;
    private boolean canViewCustomerDetails;
    private boolean canViewCustomers;
    private boolean canViewStatements;
    private boolean canViewRevenueSummary;
    private boolean canViewPdf;
    private boolean canEditPdf;
    private boolean canEditCustomers;
    private boolean canEditStatements;
    private List<String> allowedCustomerIds = new ArrayList<>();
    private List<String> allowedDays = new ArrayList<>();

    public Long getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(Long employeeId) {
        this.employeeId = employeeId;
    }

    public long getCompanyId() {
        return companyId;
    }

    public void setCompanyId(long companyId) {
        this.companyId = companyId;
    }

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public Path getCompanyDatabasePath() {
        return companyDatabasePath;
    }

    public void setCompanyDatabasePath(Path companyDatabasePath) {
        this.companyDatabasePath = companyDatabasePath;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
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

    public boolean isCanViewCustomerDetails() {
        return canViewCustomerDetails;
    }

    public void setCanViewCustomerDetails(boolean canViewCustomerDetails) {
        this.canViewCustomerDetails = canViewCustomerDetails;
    }

    public boolean isCanViewCustomers() {
        return canViewCustomers;
    }

    public void setCanViewCustomers(boolean canViewCustomers) {
        this.canViewCustomers = canViewCustomers;
    }

    public boolean isCanViewStatements() {
        return canViewStatements;
    }

    public void setCanViewStatements(boolean canViewStatements) {
        this.canViewStatements = canViewStatements;
    }

    public boolean isCanViewRevenueSummary() {
        return canViewRevenueSummary;
    }

    public void setCanViewRevenueSummary(boolean canViewRevenueSummary) {
        this.canViewRevenueSummary = canViewRevenueSummary;
    }

    public boolean isCanViewPdf() {
        return canViewPdf;
    }

    public void setCanViewPdf(boolean canViewPdf) {
        this.canViewPdf = canViewPdf;
    }

    public boolean isCanEditPdf() {
        return canEditPdf;
    }

    public void setCanEditPdf(boolean canEditPdf) {
        this.canEditPdf = canEditPdf;
    }

    public boolean isCanEditCustomers() {
        return canEditCustomers;
    }

    public void setCanEditCustomers(boolean canEditCustomers) {
        this.canEditCustomers = canEditCustomers;
    }

    public boolean isCanEditStatements() {
        return canEditStatements;
    }

    public void setCanEditStatements(boolean canEditStatements) {
        this.canEditStatements = canEditStatements;
    }

    public List<String> getAllowedCustomerIds() {
        return allowedCustomerIds;
    }

    public void setAllowedCustomerIds(List<String> allowedCustomerIds) {
        this.allowedCustomerIds = allowedCustomerIds;
    }

    public List<String> getAllowedDays() {
        return allowedDays;
    }

    public void setAllowedDays(List<String> allowedDays) {
        this.allowedDays = allowedDays;
    }

    public String getFullName() {
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();
        return (first + " " + last).trim();
    }

    @Override
    public String toString() {
        String idPart = employeeId == null ? "New" : String.valueOf(employeeId);
        return idPart + " - " + getFullName() + " (" + (username == null ? "" : username) + ")";
    }
}
