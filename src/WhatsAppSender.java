import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public class WhatsAppSender {

    private static final String PROFILE_DIR = "wa-profile";

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("""
                Usage:
                  java -jar whatsapp-sender.jar <phoneNumber> "<message>"

                Example:
                  java -jar whatsapp-sender.jar 94771234567 "Hello from Java"

                Notes:
                  - phoneNumber must include country code (no +, no spaces)
                  - First run: scan QR code in the opened Chrome window
                """);
            return;
        }

        String phone = normalizePhone(args[0]);
        String message = args[1];



        Path profileDir = Path.of(PROFILE_DIR).toAbsolutePath();
        try {
            Files.createDirectories(profileDir);
        } catch (Exception e) {
            System.err.println("Failed to create profile folder: " + profileDir);
            e.printStackTrace();
            return;
        }

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--user-data-dir=" + profileDir);
        options.addArguments("--profile-directory=Default");
        options.addArguments("--disable-notifications");
        options.addArguments("--start-maximized");

        WebDriver driver = new ChromeDriver(options);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(60));

        try {
            // 1) Open WhatsApp Web
            driver.get("https://web.whatsapp.com");
            waitUntilLoggedInOrQr(wait);

            // 2) Open direct chat URL with prefilled message
            String encodedMsg = URLEncoder.encode(message, StandardCharsets.UTF_8);
            String sendUrl = "https://web.whatsapp.com/send?phone=" + phone + "&text=" + encodedMsg + "&app_absent=0";
            driver.get(sendUrl);


            closeAnyDialog(driver);


            clickIfPresent(driver, By.xpath("//button//*[contains(text(),'Continue')]/ancestor::button"));
            clickIfPresent(driver, By.xpath("//a[contains(.,'Continue') or contains(.,'continue')]"));

            closeAnyDialog(driver);

            // 3) Send with retry (handles stale + click intercepted)
            boolean sent = sendMessageWithRetry(driver, wait, message);

            if (sent) {
                System.out.println("✅ Message sent to: " + phone);
            } else {
                System.out.println("⚠️ Could not confirm send. Check the opened WhatsApp Web window.");
            }

            Thread.sleep(1000);

        } catch (TimeoutException te) {
            System.err.println("❌ Timeout. WhatsApp Web didn't load properly.");
            System.err.println("Tip: If first run, scan the QR code and run again.");
            te.printStackTrace();
        } catch (Exception e) {
            System.err.println("❌ Failed to send message.");
            e.printStackTrace();
        } finally {
            driver.quit();
        }
    }

    // -------------------- Sending logic with retry --------------------

    private static boolean sendMessageWithRetry(WebDriver driver, WebDriverWait wait, String message) throws InterruptedException {
        By messageBox = By.xpath("//footer//div[@contenteditable='true' and (@data-tab or @role='textbox')]");

        // Try a few times because WhatsApp frequently re-renders DOM
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                closeAnyDialog(driver);

                WebElement input = wait.until(ExpectedConditions.presenceOfElementLocated(messageBox));
                wait.until(ExpectedConditions.elementToBeClickable(input));

                // Click input safely
                try {
                    input.click();
                } catch (ElementClickInterceptedException e) {
                    closeAnyDialog(driver);
                    ((JavascriptExecutor) driver).executeScript("arguments[0].click();", input);
                }

                // If text not prefilled, type it
                String current = safeGetText(input);
                if (current == null || current.isBlank()) {
                    input.sendKeys(message);
                }

                // Send
                input.sendKeys(Keys.ENTER);


                By sentBubble = By.xpath("//*[contains(@class,'message-out')]//*[contains(., " + xpathString(message) + ")]");
                new WebDriverWait(driver, Duration.ofSeconds(12))
                        .until(ExpectedConditions.presenceOfElementLocated(sentBubble));

                return true;

            } catch (StaleElementReferenceException sere) {
                // DOM changed; retry
                Thread.sleep(300);
            } catch (ElementClickInterceptedException ecie) {
                // Popup overlay; close and retry
                closeAnyDialog(driver);
                Thread.sleep(300);
            } catch (TimeoutException te) {
                // If message box never becomes available, retry a bit
                closeAnyDialog(driver);
                Thread.sleep(400);
            }
        }
        return false;
    }

    private static String safeGetText(WebElement el) {
        try { return el.getText(); } catch (StaleElementReferenceException e) { return ""; }
    }

    // -------------------- Helpers --------------------

    private static String normalizePhone(String phone) {
        return phone.replaceAll("[^0-9]", "");
    }

    private static void waitUntilLoggedInOrQr(WebDriverWait wait) {
        wait.until(driver -> {
            try {
                boolean qrShown = !driver.findElements(By.cssSelector("canvas")).isEmpty()
                        && driver.getPageSource().toLowerCase().contains("scan");
                boolean uiReady = !driver.findElements(By.cssSelector("div[contenteditable='true']")).isEmpty();
                return uiReady || qrShown;
            } catch (Exception ex) {
                return false;
            }
        });
    }

    private static void clickIfPresent(WebDriver driver, By by) {
        try {
            var els = driver.findElements(by);
            if (!els.isEmpty()) {
                try { els.get(0).click(); Thread.sleep(250); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private static void closeAnyDialog(WebDriver driver) {
        // ESC closes many WhatsApp popups
        try {
            driver.switchTo().activeElement().sendKeys(Keys.ESCAPE);
            Thread.sleep(120);
            driver.switchTo().activeElement().sendKeys(Keys.ESCAPE);
            Thread.sleep(120);
        } catch (Exception ignored) {}

        try {
            var dialogs = driver.findElements(By.cssSelector("div[role='dialog']"));
            if (!dialogs.isEmpty()) {
                String[] xpaths = new String[] {
                        "//div[@role='dialog']//button//*[contains(text(),'Not now')]/ancestor::button",
                        "//div[@role='dialog']//button//*[contains(text(),'Cancel')]/ancestor::button",
                        "//div[@role='dialog']//button//*[contains(text(),'OK')]/ancestor::button",
                        "//div[@role='dialog']//button[@aria-label='Close']",
                        "//div[@role='dialog']//span[@data-icon='x']/ancestor::*[self::button or self::div]"
                };
                for (String xp : xpaths) {
                    var els = driver.findElements(By.xpath(xp));
                    if (!els.isEmpty()) {
                        try { els.get(0).click(); Thread.sleep(200); } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    // Safe string for XPath contains(...)
    private static String xpathString(String s) {
        if (!s.contains("'")) return "'" + s + "'";
        String[] parts = s.split("'");
        StringBuilder sb = new StringBuilder("concat(");
        for (int i = 0; i < parts.length; i++) {
            sb.append("'").append(parts[i]).append("'");
            if (i < parts.length - 1) sb.append(",\"'\",");
        }
        sb.append(")");
        return sb.toString();
    }
}
