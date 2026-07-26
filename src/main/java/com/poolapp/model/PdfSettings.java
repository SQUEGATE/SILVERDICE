package com.poolapp.model;

import java.awt.Color;

public class PdfSettings {
    // Fonts
    private String titleFont = "TIMES_BOLD";
    private int titleSize = 18;
    private String headerFont = "TIMES_BOLD";
    private int headerSize = 12;
    private String bodyFont = "TIMES_ROMAN";
    private int bodySize = 10;
    private String footerFont = "TIMES_ROMAN";
    private int footerSize = 9;

    // Colors (stored as hex strings)
    private String titleColor = "#000000"; // Black
    private String headerColor = "#000000"; // Black
    private String bodyColor = "#000000"; // Black
    private String footerColor = "#666666"; // Gray

    // Layout
    private float marginLeft = 50;
    private float marginRight = 50;
    private float marginTop = 50;
    private float marginBottom = 50;

    // Position offsets for different elements
    private float companyInfoOffsetX = 0;
    private float companyInfoOffsetY = 0;
    private float titleOffsetX = 0;
    private float titleOffsetY = 0;
    private float headerOffsetX = 0;
    private float headerOffsetY = 0;
    private float bodyOffsetX = 0;
    private float bodyOffsetY = 0;
    private float tableOffsetX = 0;
    private float tableOffsetY = 0;
    private float footerOffsetX = 0;
    private float footerOffsetY = 0;

    // Content
    private String companyName = "Pool Service Company";
    private String companyAddress = "";
    private String companyPhone = "";
    private String companyEmail = "";
    private String logoPath = ""; // Path to logo image file
    private boolean showLogo = false;
    private float logoX = 400;
    private float logoY = 700;
    private float logoWidth = 100;
    private float logoHeight = 50;

    // Table settings
    private boolean showTableBorders = true;
    private String tableBorderColor = "#CCCCCC";
    private float tableHeaderSpacing = 20;
    private float tableRowSpacing = 12;

    // Footer
    private String footerText = "Thank you for choosing our pool service!";
    private boolean showGenerationDate = true;

    // Page settings
    private String pageSize = "LETTER"; // LETTER, A4, LEGAL
    private boolean landscape = false;

    // Getters and setters
    public String getTitleFont() { return titleFont; }
    public void setTitleFont(String titleFont) { this.titleFont = titleFont; }

    public int getTitleSize() { return titleSize; }
    public void setTitleSize(int titleSize) { this.titleSize = titleSize; }

    public String getHeaderFont() { return headerFont; }
    public void setHeaderFont(String headerFont) { this.headerFont = headerFont; }

    public int getHeaderSize() { return headerSize; }
    public void setHeaderSize(int headerSize) { this.headerSize = headerSize; }

    public String getBodyFont() { return bodyFont; }
    public void setBodyFont(String bodyFont) { this.bodyFont = bodyFont; }

    public int getBodySize() { return bodySize; }
    public void setBodySize(int bodySize) { this.bodySize = bodySize; }

    public String getFooterFont() { return footerFont; }
    public void setFooterFont(String footerFont) { this.footerFont = footerFont; }

    public int getFooterSize() { return footerSize; }
    public void setFooterSize(int footerSize) { this.footerSize = footerSize; }

    public String getTitleColor() { return titleColor; }
    public void setTitleColor(String titleColor) { this.titleColor = titleColor; }

    public String getHeaderColor() { return headerColor; }
    public void setHeaderColor(String headerColor) { this.headerColor = headerColor; }

    public String getBodyColor() { return bodyColor; }
    public void setBodyColor(String bodyColor) { this.bodyColor = bodyColor; }

    public String getFooterColor() { return footerColor; }
    public void setFooterColor(String footerColor) { this.footerColor = footerColor; }

    public float getMarginLeft() { return marginLeft; }
    public void setMarginLeft(float marginLeft) { this.marginLeft = marginLeft; }

    public float getMarginRight() { return marginRight; }
    public void setMarginRight(float marginRight) { this.marginRight = marginRight; }

    public float getMarginTop() { return marginTop; }
    public void setMarginTop(float marginTop) { this.marginTop = marginTop; }

    public float getMarginBottom() { return marginBottom; }
    public void setMarginBottom(float marginBottom) { this.marginBottom = marginBottom; }

    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }

    public String getCompanyAddress() { return companyAddress; }
    public void setCompanyAddress(String companyAddress) { this.companyAddress = companyAddress; }

    public String getCompanyPhone() { return companyPhone; }
    public void setCompanyPhone(String companyPhone) { this.companyPhone = companyPhone; }

    public String getCompanyEmail() { return companyEmail; }
    public void setCompanyEmail(String companyEmail) { this.companyEmail = companyEmail; }

    public String getLogoPath() { return logoPath; }
    public void setLogoPath(String logoPath) { this.logoPath = logoPath; }

    public boolean isShowLogo() { return showLogo; }
    public void setShowLogo(boolean showLogo) { this.showLogo = showLogo; }

    public float getLogoX() { return logoX; }
    public void setLogoX(float logoX) { this.logoX = logoX; }

    public float getLogoY() { return logoY; }
    public void setLogoY(float logoY) { this.logoY = logoY; }

    public float getLogoWidth() { return logoWidth; }
    public void setLogoWidth(float logoWidth) { this.logoWidth = logoWidth; }

    public float getLogoHeight() { return logoHeight; }
    public void setLogoHeight(float logoHeight) { this.logoHeight = logoHeight; }

    public boolean isShowTableBorders() { return showTableBorders; }
    public void setShowTableBorders(boolean showTableBorders) { this.showTableBorders = showTableBorders; }

    public String getTableBorderColor() { return tableBorderColor; }
    public void setTableBorderColor(String tableBorderColor) { this.tableBorderColor = tableBorderColor; }

    public float getTableHeaderSpacing() { return tableHeaderSpacing; }
    public void setTableHeaderSpacing(float tableHeaderSpacing) { this.tableHeaderSpacing = tableHeaderSpacing; }

    public float getTableRowSpacing() { return tableRowSpacing; }
    public void setTableRowSpacing(float tableRowSpacing) { this.tableRowSpacing = tableRowSpacing; }

    public String getFooterText() { return footerText; }
    public void setFooterText(String footerText) { this.footerText = footerText; }

    public boolean isShowGenerationDate() { return showGenerationDate; }
    public void setShowGenerationDate(boolean showGenerationDate) { this.showGenerationDate = showGenerationDate; }

    public String getPageSize() { return pageSize; }
    public void setPageSize(String pageSize) { this.pageSize = pageSize; }

    public boolean isLandscape() { return landscape; }
    public void setLandscape(boolean landscape) { this.landscape = landscape; }

    public float getCompanyInfoOffsetX() { return companyInfoOffsetX; }
    public void setCompanyInfoOffsetX(float companyInfoOffsetX) { this.companyInfoOffsetX = companyInfoOffsetX; }

    public float getCompanyInfoOffsetY() { return companyInfoOffsetY; }
    public void setCompanyInfoOffsetY(float companyInfoOffsetY) { this.companyInfoOffsetY = companyInfoOffsetY; }

    public float getTitleOffsetX() { return titleOffsetX; }
    public void setTitleOffsetX(float titleOffsetX) { this.titleOffsetX = titleOffsetX; }

    public float getTitleOffsetY() { return titleOffsetY; }
    public void setTitleOffsetY(float titleOffsetY) { this.titleOffsetY = titleOffsetY; }

    public float getHeaderOffsetX() { return headerOffsetX; }
    public void setHeaderOffsetX(float headerOffsetX) { this.headerOffsetX = headerOffsetX; }

    public float getHeaderOffsetY() { return headerOffsetY; }
    public void setHeaderOffsetY(float headerOffsetY) { this.headerOffsetY = headerOffsetY; }

    public float getBodyOffsetX() { return bodyOffsetX; }
    public void setBodyOffsetX(float bodyOffsetX) { this.bodyOffsetX = bodyOffsetX; }

    public float getBodyOffsetY() { return bodyOffsetY; }
    public void setBodyOffsetY(float bodyOffsetY) { this.bodyOffsetY = bodyOffsetY; }

    public float getTableOffsetX() { return tableOffsetX; }
    public void setTableOffsetX(float tableOffsetX) { this.tableOffsetX = tableOffsetX; }

    public float getTableOffsetY() { return tableOffsetY; }
    public void setTableOffsetY(float tableOffsetY) { this.tableOffsetY = tableOffsetY; }

    public float getFooterOffsetX() { return footerOffsetX; }
    public void setFooterOffsetX(float footerOffsetX) { this.footerOffsetX = footerOffsetX; }

    public float getFooterOffsetY() { return footerOffsetY; }
    public void setFooterOffsetY(float footerOffsetY) { this.footerOffsetY = footerOffsetY; }

    // Utility methods
    public Color getTitleColorAsColor() {
        return Color.decode(titleColor);
    }

    public Color getHeaderColorAsColor() {
        return Color.decode(headerColor);
    }

    public Color getBodyColorAsColor() {
        return Color.decode(bodyColor);
    }

    public Color getFooterColorAsColor() {
        return Color.decode(footerColor);
    }

    public Color getTableBorderColorAsColor() {
        return Color.decode(tableBorderColor);
    }
}