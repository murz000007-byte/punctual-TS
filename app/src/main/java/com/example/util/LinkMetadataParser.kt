package com.example.util

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

data class ExtractedMetadata(
    val title: String,
    val imageUrl: String?,
    val siteName: String?,
    val originalUrl: String,
    val price: String? = null,
    val brand: String? = null,
    val rating: String? = null
)

data class WebViewResult(val html: String?, val finalUrl: String?)

object LinkMetadataParser {

    suspend fun fetchHtmlWithWebView(context: Context, url: String): WebViewResult {
        return withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<WebViewResult>()
            val webView = WebView(context)
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                userAgentString = "Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
            }
            webView.addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun onPageReady(html: String) {
                    if (deferred.isActive) {
                        deferred.complete(WebViewResult(html, webView.url))
                    }
                }
            }, "AndroidHtmlLoader")

            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                    return false // Let WebView load and redirect inside the webview
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript("""
                        (function() {
                            var hasCalled = false;
                            function finish() {
                                if (hasCalled) return;
                                hasCalled = true;
                                try {
                                    AndroidHtmlLoader.onPageReady(document.documentElement.outerHTML);
                                } catch(e) {}
                            }

                            function checkImmediate() {
                                var hasData = !!(document.getElementById('__NEXT_DATA__') || window.__NEXT_DATA__);
                                return document.readyState === 'complete' && hasData;
                            }

                            if (checkImmediate()) {
                                finish();
                                return;
                            }

                            var safetyTimer = setTimeout(finish, 8000);
                            var idleTimer = setTimeout(finish, 2000);

                            var observer = new MutationObserver(function() {
                                clearTimeout(idleTimer);
                                idleTimer = setTimeout(function() {
                                    if (document.readyState === 'complete') {
                                        observer.disconnect();
                                        finish();
                                    }
                                }, 1000);
                            });

                            try {
                                observer.observe(document, { childList: true, subtree: true });
                            } catch(e) {
                                setTimeout(finish, 3000);
                            }
                        })()
                    """.trimIndent(), null)
                }
            }
            webView.loadUrl(url)
            
            val result = try {
                kotlinx.coroutines.withTimeout(20000) {
                    deferred.await()
                }
            } catch (e: Exception) {
                // If it times out, let's try to grab current outerHTML anyway as a last resort
                val htmlResult = CompletableDeferred<String?>()
                try {
                    webView.evaluateJavascript("document.documentElement.outerHTML") { html ->
                        val cleanHtml = if (html != null && html.startsWith("\"") && html.endsWith("\"")) {
                            try {
                                JSONObject("{\"h\":$html}").getString("h")
                            } catch (ex: Exception) {
                                html
                            }
                        } else {
                            html
                        }
                        htmlResult.complete(cleanHtml)
                    }
                    val finalHtml = kotlinx.coroutines.withTimeoutOrNull(2000) { htmlResult.await() }
                    WebViewResult(finalHtml, webView.url)
                } catch (ex: Exception) {
                    WebViewResult(null, webView.url)
                }
            } finally {
                webView.stopLoading()
                webView.destroy()
            }
            result
        }
    }

    private fun parseMeeshoWithPriorities(doc: Document): PartialMetadata {
        val ogTitle = getMetaTag(doc, "property", "og:title")
            ?: getMetaTag(doc, "name", "twitter:title")
            ?: getMetaTag(doc, "name", "og:title")
            ?: getMetaTag(doc, "property", "twitter:title")

        val ogImage = getMetaTag(doc, "property", "og:image")
            ?: getMetaTag(doc, "name", "twitter:image")
            ?: getMetaTag(doc, "name", "og:image")
            ?: getMetaTag(doc, "property", "twitter:image")

        val ogDesc = getMetaTag(doc, "property", "og:description")
            ?: getMetaTag(doc, "name", "twitter:description")
            ?: getMetaTag(doc, "name", "description")
            ?: getMetaTag(doc, "property", "twitter:description")

        var ogPrice: String? = null
        val priceAmount = getMetaTag(doc, "property", "product:price:amount")
            ?: getMetaTag(doc, "property", "og:price:amount")
            ?: getMetaTag(doc, "name", "twitter:data1")
            ?: getMetaTag(doc, "property", "twitter:data1")

        if (priceAmount != null && priceAmount.isNotEmpty()) {
            val currency = getMetaTag(doc, "property", "product:price:currency")
                ?: getMetaTag(doc, "property", "og:price:currency")
                ?: "₹"
            ogPrice = if (priceAmount.all { it.isDigit() || it == '.' }) "$currency$priceAmount" else priceAmount
        }

        // Try extracting price from the description tag as fallback (e.g. "at ₹299 only on Meesho")
        if (ogPrice == null && ogDesc != null) {
            val priceRegex = "₹\\s*\\d+(?:,\\d+)*(?:\\.\\d+)?".toRegex()
            val match = priceRegex.find(ogDesc)
            if (match != null) {
                ogPrice = match.value
            }
        }

        val jsonLd = parseJsonLd(doc)
        val nextData = parseNextDataForMeesho(doc)

        val finalTitle = nextData?.title?.takeIf { it.isNotEmpty() }
            ?: jsonLd?.title?.takeIf { it.isNotEmpty() }
            ?: ogTitle?.takeIf { it.isNotEmpty() }

        val finalImage = nextData?.imageUrl?.takeIf { it.isNotEmpty() }
            ?: jsonLd?.imageUrl?.takeIf { it.isNotEmpty() }
            ?: ogImage?.takeIf { it.isNotEmpty() }

        val finalPrice = nextData?.price?.takeIf { it.isNotEmpty() }
            ?: jsonLd?.price?.takeIf { it.isNotEmpty() }
            ?: ogPrice?.takeIf { it.isNotEmpty() }

        val finalRating = nextData?.rating?.takeIf { it.isNotEmpty() }
            ?: jsonLd?.rating?.takeIf { it.isNotEmpty() }

        return PartialMetadata(
            title = finalTitle,
            imageUrl = finalImage,
            price = finalPrice,
            brand = "Meesho",
            rating = finalRating
        )
    }

    fun extractUrlFromText(text: String): String {
        val regex = "https?://[^\\s]+".toRegex()
        val match = regex.find(text)
        if (match != null) {
            var url = match.value
            while (url.isNotEmpty() && (url.endsWith(".") || url.endsWith(",") || url.endsWith("!") || url.endsWith("?") || url.endsWith(")") || url.endsWith("]"))) {
                url = url.substring(0, url.length - 1)
            }
            return url
        }
        return text.trim()
    }

    fun extractTitleFromSharedText(text: String): String? {
        val cleanedText = text.trim()
        val url = extractUrlFromText(cleanedText)
        if (url == cleanedText) return null // No extra text, just raw URL

        var textWithoutUrl = cleanedText.replace(url, "").trim()
        textWithoutUrl = textWithoutUrl.replace("\n", " ").replace("\r", " ")
        while (textWithoutUrl.contains("  ")) {
            textWithoutUrl = textWithoutUrl.replace("  ", " ")
        }

        val meeshoPattern1 = "Take a look at this (.*) on Meesho!".toRegex(RegexOption.IGNORE_CASE)
        val meeshoPattern2 = "Shop now on Meesho: (.*)".toRegex(RegexOption.IGNORE_CASE)
        val flipkartPattern = "Take a look at this (.*) on Flipkart".toRegex(RegexOption.IGNORE_CASE)
        val myntraPattern = "Check out (.*) on Myntra".toRegex(RegexOption.IGNORE_CASE)
        val genericPattern1 = "Check this out: (.*)".toRegex(RegexOption.IGNORE_CASE)
        val genericPattern2 = "Check this out! (.*)".toRegex(RegexOption.IGNORE_CASE)

        val match = meeshoPattern1.find(textWithoutUrl)
            ?: meeshoPattern2.find(textWithoutUrl)
            ?: flipkartPattern.find(textWithoutUrl)
            ?: myntraPattern.find(textWithoutUrl)
            ?: genericPattern1.find(textWithoutUrl)
            ?: genericPattern2.find(textWithoutUrl)

        if (match != null && match.groupValues.size > 1) {
            val candidate = match.groupValues[1].trim()
            if (candidate.isNotEmpty() && candidate.length < 150) {
                return candidate
            }
        }

        val words = textWithoutUrl.split(" ").filter { it.isNotEmpty() }
        if (words.size >= 3) {
            var cleanCandidate = textWithoutUrl
            val boilerplate = listOf(
                "Take a look at this", "Check this out", "Shop now on Meesho",
                "on Meesho!", "on Flipkart", "on Myntra", "on Amazon",
                "Hey! Look at this", "Check out this", "I'm sharing this"
            )
            for (bp in boilerplate) {
                cleanCandidate = cleanCandidate.replace(bp, "").trim()
            }
            cleanCandidate = cleanCandidate.trim { it in listOf('-', '|', ':', ',', '.', '!', ' ') }
            if (cleanCandidate.isNotEmpty() && cleanCandidate.length in 5..150) {
                return cleanCandidate
            }
        }

        return null
    }

    suspend fun fetchMetadata(context: Context, rawInput: String): ExtractedMetadata = withContext(Dispatchers.IO) {
        val extractedUrl = extractUrlFromText(rawInput)
        val cleanUrl = if (!extractedUrl.startsWith("http://") && !extractedUrl.startsWith("https://")) {
            "https://$extractedUrl"
        } else {
            extractedUrl
        }

        val sharedTextTitle = extractTitleFromSharedText(rawInput)
        var finalResolvedUrl = cleanUrl

        try {
            val uri = URI(cleanUrl)
            val host = uri.host ?: ""
            val domain = host.replace("www.", "")

            var document: Document? = null
            val isMeesho = domain.contains("meesho", ignoreCase = true)

            if (isMeesho) {
                try {
                    val doc = Jsoup.connect(cleanUrl)
                        .userAgent("Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .header("Cache-Control", "max-age=0")
                        .header("Connection", "keep-alive")
                        .header("Sec-Ch-Ua", "\"Not A(Brand\";v=\"99\", \"Google Chrome\";v=\"121\", \"Chromium\";v=\"121\"")
                        .header("Sec-Ch-Ua-Mobile", "?1")
                        .header("Sec-Ch-Ua-Platform", "\"Android\"")
                        .header("Sec-Fetch-Dest", "document")
                        .header("Sec-Fetch-Mode", "navigate")
                        .header("Sec-Fetch-Site", "none")
                        .header("Sec-Fetch-User", "?1")
                        .header("Upgrade-Insecure-Requests", "1")
                        .referrer("https://www.google.com")
                        .timeout(10000)
                        .followRedirects(true)
                        .get()

                    val title = doc.title() ?: ""
                    if (title.isNotEmpty() && !title.contains("Cloudflare", ignoreCase = true) && !title.contains("Attention Required", ignoreCase = true) && !title.contains("Forbidden", ignoreCase = true) && !title.contains("Just a moment", ignoreCase = true)) {
                        document = doc
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                if (document == null) {
                    val webViewResult = fetchHtmlWithWebView(context, cleanUrl)
                    if (webViewResult.html != null && webViewResult.html.isNotEmpty()) {
                        document = Jsoup.parse(webViewResult.html, cleanUrl)
                        if (webViewResult.finalUrl != null) {
                            finalResolvedUrl = webViewResult.finalUrl
                        }
                    }
                }
            } else {
                document = Jsoup.connect(cleanUrl)
                    .userAgent("Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Cache-Control", "max-age=0")
                    .header("Connection", "keep-alive")
                    .header("Sec-Ch-Ua", "\"Not A(Brand\";v=\"99\", \"Google Chrome\";v=\"121\", \"Chromium\";v=\"121\"")
                    .header("Sec-Ch-Ua-Mobile", "?1")
                    .header("Sec-Ch-Ua-Platform", "\"Android\"")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "none")
                    .header("Sec-Fetch-User", "?1")
                    .header("Upgrade-Insecure-Requests", "1")
                    .referrer("https://www.google.com")
                    .timeout(15000)
                    .followRedirects(true)
                    .get()
            }

            val finalDoc = document ?: throw Exception("Could not fetch page document")

            // 1. Try Site-Specific Parsers first
            val siteSpecific = parseSiteSpecific(finalDoc, domain)

            // 2. Extract JSON-LD (Schema.org Product)
            val jsonLd = parseJsonLd(finalDoc)

            // 3. Extract Open Graph
            val ogTitle = getMetaTag(finalDoc, "property", "og:title")
            val ogImage = getMetaTag(finalDoc, "property", "og:image")
            val ogSiteName = getMetaTag(finalDoc, "property", "og:site_name")

            // 4. Fallback HTML standard
            val htmlTitle = finalDoc.title()
            val metaDescription = getMetaTag(finalDoc, "name", "description")

            // Site name determination
            val finalSiteName = when {
                ogSiteName?.isNotEmpty() == true -> ogSiteName
                domain.contains("amazon", ignoreCase = true) -> "Amazon"
                domain.contains("flipkart", ignoreCase = true) -> "Flipkart"
                domain.contains("myntra", ignoreCase = true) -> "Myntra"
                domain.contains("ajio", ignoreCase = true) -> "Ajio"
                domain.contains("meesho", ignoreCase = true) -> "Meesho"
                domain.contains("snapdeal", ignoreCase = true) -> "Snapdeal"
                domain.contains("nykaa", ignoreCase = true) -> "Nykaa"
                domain.contains("reliance", ignoreCase = true) -> "Reliance Trends"
                else -> domain.split(".").firstOrNull()?.replaceFirstChar { it.uppercase() } ?: "Web"
            }

            // Title determination
            var rawTitle = when {
                siteSpecific.title?.isNotEmpty() == true -> siteSpecific.title
                jsonLd?.title?.isNotEmpty() == true -> jsonLd.title
                ogTitle?.isNotEmpty() == true -> ogTitle
                htmlTitle.isNotEmpty() -> htmlTitle
                else -> "Product Link"
            }
            rawTitle = cleanTitle(rawTitle, finalSiteName)
            if (rawTitle == "Product Link" || rawTitle.isEmpty()) {
                rawTitle = sharedTextTitle ?: extractTitleFromUrl(finalResolvedUrl) ?: "Product Link"
            }

            // Image determination
            val finalImage = when {
                siteSpecific.imageUrl?.isNotEmpty() == true -> siteSpecific.imageUrl
                jsonLd?.imageUrl?.isNotEmpty() == true -> jsonLd.imageUrl
                ogImage?.isNotEmpty() == true -> ogImage
                else -> findProductImageFallback(finalDoc, finalResolvedUrl)
            }

            // Price, Brand, Rating determination
            val finalPrice = siteSpecific.price ?: jsonLd?.price ?: extractPriceFallback(finalDoc, domain)
            val finalBrand = siteSpecific.brand ?: jsonLd?.brand ?: extractBrandFallback(finalDoc, domain, rawTitle)
            val finalRating = siteSpecific.rating ?: jsonLd?.rating ?: extractRatingFallback(finalDoc)

            ExtractedMetadata(
                title = rawTitle,
                imageUrl = finalImage,
                siteName = finalSiteName,
                originalUrl = finalResolvedUrl,
                price = finalPrice,
                brand = finalBrand ?: (if (domain.contains("meesho", ignoreCase = true)) "Meesho" else null),
                rating = finalRating
            )

        } catch (e: Exception) {
            e.printStackTrace()
            // High reliability fallback
            val domain = try {
                URI(finalResolvedUrl).host?.replace("www.", "") ?: "Web"
            } catch (ex: Exception) {
                "Web"
            }
            
            val finalSiteName = when {
                domain.contains("amazon", ignoreCase = true) -> "Amazon"
                domain.contains("flipkart", ignoreCase = true) -> "Flipkart"
                domain.contains("myntra", ignoreCase = true) -> "Myntra"
                domain.contains("ajio", ignoreCase = true) -> "Ajio"
                domain.contains("meesho", ignoreCase = true) -> "Meesho"
                domain.contains("snapdeal", ignoreCase = true) -> "Snapdeal"
                domain.contains("nykaa", ignoreCase = true) -> "Nykaa"
                else -> domain.split(".").firstOrNull()?.replaceFirstChar { it.uppercase() } ?: "Web"
            }

            val extractedTitle = sharedTextTitle ?: extractTitleFromUrl(finalResolvedUrl) ?: "Product Link"
            val finalBrand = if (domain.contains("meesho", ignoreCase = true)) "Meesho" else null

            ExtractedMetadata(
                title = extractedTitle,
                imageUrl = null,
                siteName = finalSiteName,
                originalUrl = finalResolvedUrl,
                price = null,
                brand = finalBrand,
                rating = null
            )
        }
    }

    private fun getMetaTag(document: Document, attributeKey: String, attributeValue: String): String? {
        val element = document.select("meta[$attributeKey=$attributeValue]").firstOrNull()
        return element?.attr("content")
    }

    private fun cleanTitle(title: String, siteName: String): String {
        var clean = title.trim()
        // Remove common product details suffix often found on Amazon/Flipkart
        val suffixes = listOf(
            "Online at Low Prices in India",
            "Buy Online",
            "at Flipkart.com",
            "| Flipkart",
            "- Buy",
            "online at Myntra",
            "| Amazon.in",
            "- Amazon.com",
            "on AJIO",
            "- Meesho"
        )
        for (suffix in suffixes) {
            val idx = clean.indexOf(suffix, ignoreCase = true)
            if (idx != -1) {
                clean = clean.substring(0, idx).trim()
            }
        }
        // Remove trailing hyphens or pipes
        if (clean.endsWith("-") || clean.endsWith("|")) {
            clean = clean.substring(0, clean.length - 1).trim()
        }
        return clean
    }

    private fun parseSiteSpecific(doc: Document, domain: String): PartialMetadata {
        val title: String?
        val image: String?
        var price: String? = null
        var brand: String? = null
        var rating: String? = null

        if (domain.contains("amazon", ignoreCase = true)) {
            title = doc.select("#productTitle").text().trim().takeIf { it.isNotEmpty() }
            image = (doc.select("#landingImage").attr("src").takeIf { it.isNotEmpty() }
                ?: doc.select("#imgBlkFront").attr("src").takeIf { it.isNotEmpty() }
                ?: doc.select("img#main-image").attr("src").takeIf { it.isNotEmpty() })
            price = doc.select(".a-price .a-offscreen").firstOrNull()?.text()?.trim()
                ?: doc.select("#priceblock_ourprice").text().trim()
                ?: doc.select("#priceblock_dealprice").text().trim()
            brand = doc.select("#bylineInfo").text().replace("Brand:", "").replace("Visit the", "").trim()
            rating = doc.select("#acrPopover").attr("title").replace("out of 5 stars", "").trim()
                .takeIf { it.isNotEmpty() } ?: doc.select(".a-star-4-5, .a-star-5, .a-star-4").text().trim()
            return PartialMetadata(title, image, price, brand, rating)
        }

        if (domain.contains("flipkart", ignoreCase = true)) {
            title = doc.select("h1.B_NuCI").text().trim().takeIf { it.isNotEmpty() }
                ?: doc.select(".yhB1nd").text().trim().takeIf { it.isNotEmpty() }
            image = doc.select("img._396cs4._2amPTt").attr("src").takeIf { it.isNotEmpty() }
                ?: doc.select("._3kid_b img").attr("src").takeIf { it.isNotEmpty() }
            price = doc.select("._30jeq3._16Jk6d").text().trim().takeIf { it.isNotEmpty() }
            brand = doc.select(".G6XKyg").text().trim().takeIf { it.isNotEmpty() }
            rating = doc.select("._3LWZlK").firstOrNull()?.text()?.trim()
            return PartialMetadata(title, image, price, brand, rating)
        }

        if (domain.contains("myntra", ignoreCase = true)) {
            title = doc.select("h1.pdp-title").text().trim().takeIf { it.isNotEmpty() }
            image = doc.select(".image-grid-image").firstOrNull()?.attr("style")?.let { style ->
                // extract url from style background-image
                val regex = "url\\(\"(.*?)\"\\)".toRegex()
                regex.find(style)?.groupValues?.get(1)
            }
            price = doc.select(".pdp-price").firstOrNull()?.text()?.trim()
            brand = doc.select(".pdp-name").text().trim().takeIf { it.isNotEmpty() }
            rating = doc.select(".index-overallRating .index-averageRating").text().trim()
            return PartialMetadata(title, image, price, brand, rating)
        }

        if (domain.contains("ajio", ignoreCase = true)) {
            title = doc.select("h1.prod-name").text().trim().takeIf { it.isNotEmpty() }
            image = doc.select("img.img-alignment").attr("src").takeIf { it.isNotEmpty() }
            price = doc.select(".prod-sp").text().trim().takeIf { it.isNotEmpty() }
            brand = doc.select("h2.brand-name").text().trim().takeIf { it.isNotEmpty() }
            return PartialMetadata(title, image, price, brand, rating)
        }

        if (domain.contains("meesho", ignoreCase = true)) {
            return parseMeeshoWithPriorities(doc)
        }

        return PartialMetadata(null, null, null, null, null)
    }

    private fun parseJsonLd(doc: Document): PartialMetadata? {
        try {
            val scripts = doc.select("script[type=application/ld+json]")
            for (script in scripts) {
                val jsonText = script.html().trim()
                if (jsonText.isEmpty()) continue
                
                // Parse either as single object or array
                if (jsonText.startsWith("[")) {
                    val array = JSONArray(jsonText)
                    for (i in 0 until array.length()) {
                        val partial = parseJsonLdObject(array.optJSONObject(i))
                        if (partial != null) return partial
                    }
                } else if (jsonText.startsWith("{")) {
                    val obj = JSONObject(jsonText)
                    val partial = parseJsonLdObject(obj)
                    if (partial != null) return partial
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun parseJsonLdObject(obj: JSONObject?): PartialMetadata? {
        if (obj == null) return null
        val type = obj.optString("@type") ?: ""
        if (type.contains("Product", ignoreCase = true)) {
            val name = obj.optString("name")
            val image = when {
                obj.has("image") -> {
                    val imgObj = obj.get("image")
                    if (imgObj is JSONArray && imgObj.length() > 0) imgObj.optString(0)
                    else if (imgObj is JSONObject) imgObj.optString("url")
                    else imgObj.toString()
                }
                else -> null
            }
            var price: String? = null
            if (obj.has("offers")) {
                val offersObj = obj.get("offers")
                if (offersObj is JSONObject) {
                    val lowPrice = offersObj.optString("lowPrice")
                    val offerPrice = offersObj.optString("price")
                    val currency = offersObj.optString("priceCurrency")
                    price = when {
                        lowPrice.isNotEmpty() -> "$currency $lowPrice"
                        offerPrice.isNotEmpty() -> "$currency $offerPrice"
                        else -> null
                    }
                } else if (offersObj is JSONArray && offersObj.length() > 0) {
                    val firstOffer = offersObj.optJSONObject(0)
                    price = firstOffer?.optString("price")
                }
            }
            var brand: String? = null
            if (obj.has("brand")) {
                val brandObj = obj.get("brand")
                brand = if (brandObj is JSONObject) brandObj.optString("name") else brandObj.toString()
            }
            var rating: String? = null
            if (obj.has("aggregateRating")) {
                val ratingObj = obj.optJSONObject("aggregateRating")
                rating = ratingObj?.optString("ratingValue")
            }
            return PartialMetadata(
                name.takeIf { it.isNotEmpty() },
                image?.takeIf { it.isNotEmpty() },
                price,
                brand?.takeIf { it.isNotEmpty() },
                rating?.takeIf { it.isNotEmpty() }
            )
        }
        return null
    }

    private fun findProductImageFallback(doc: Document, baseUrl: String): String? {
        // Find largest image on the page that could be the product image
        val images = doc.select("img")
        var bestImgUrl: String? = null
        var maxArea = 0

        for (img in images) {
            val src = img.absUrl("src")
            if (src.isEmpty() || src.endsWith(".gif") || src.contains("logo") || src.contains("icon")) continue
            val widthAttr = img.attr("width").toIntOrNull() ?: 0
            val heightAttr = img.attr("height").toIntOrNull() ?: 0
            val area = widthAttr * heightAttr
            if (area > maxArea) {
                maxArea = area
                bestImgUrl = src
            }
        }
        return bestImgUrl ?: images.firstOrNull()?.absUrl("src")
    }

    private fun extractPriceFallback(doc: Document, domain: String): String? {
        // Search text matching typical price pattern e.g., ₹999, $49.99
        val pricePatterns = listOf(
            "₹\\s*\\d+(?:,\\d+)*(?:\\.\\d+)?",
            "\\$\\s*\\d+(?:\\.\\d{2})?"
        )
        for (pattern in pricePatterns) {
            val regex = pattern.toRegex()
            // search in specific generic elements first
            val selectors = listOf(".price", "[class*=price]", "[id*=price]", "span", "div")
            for (selector in selectors) {
                val elements = doc.select(selector)
                for (el in elements) {
                    val text = el.text().trim()
                    if (regex.containsMatchIn(text) && text.length < 20) {
                        return regex.find(text)?.value
                    }
                }
            }
        }
        return null
    }

    private fun extractBrandFallback(doc: Document, domain: String, title: String): String? {
        // Often brand is the first word of the title
        val words = title.split(" ").filter { it.length > 2 }
        return words.firstOrNull()
    }

    private fun extractRatingFallback(doc: Document): String? {
        val ratingElement = doc.select("[class*=rating], [id*=rating]").firstOrNull()
        val text = ratingElement?.text()?.trim() ?: ""
        val ratingRegex = "\\b[3-5]\\.\\d\\b".toRegex()
        return ratingRegex.find(text)?.value
    }

    fun extractTitleFromUrl(url: String): String? {
        try {
            val uri = URI(url)
            val path = uri.path ?: ""
            val segments = path.split("/").filter { it.isNotEmpty() }
            if (segments.isEmpty()) return null

            // First choice: look for a segment containing hyphens
            val hyphenatedSegments = segments.filter { it.contains("-") && !it.contains(".") }
            var slug = hyphenatedSegments.maxByOrNull { it.count { char -> char == '-' } }

            // Second choice: look for segment before "p" or "buy" if no hyphenated segment is found
            if (slug == null) {
                val pIndex = segments.indexOf("p")
                if (pIndex > 0) {
                    slug = segments[pIndex - 1]
                } else if (segments.contains("buy")) {
                    val buyIndex = segments.indexOf("buy")
                    if (buyIndex > 0) slug = segments[buyIndex - 1]
                }
            }

            // Third choice: last segment
            if (slug == null) {
                slug = segments.lastOrNull { !it.all { char -> char.isDigit() } }
            }

            if (slug != null && slug.isNotEmpty()) {
                val cleanSlug = slug.replace("?", "/").split("/").first()
                val words = cleanSlug.split("-")
                    .filter { it.isNotEmpty() && !it.all { char -> char.isDigit() } }
                if (words.isNotEmpty()) {
                    return words.joinToString(" ") { word ->
                        word.lowercase().replaceFirstChar { it.uppercase() }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun parseNextDataForMeesho(doc: Document): PartialMetadata? {
        try {
            val script = doc.select("script#__NEXT_DATA__").firstOrNull()
            var jsonText = script?.html()?.trim() ?: ""
            
            if (jsonText.isEmpty()) {
                val scripts = doc.select("script")
                for (s in scripts) {
                    val html = s.html()
                    if (html.contains("__NEXT_DATA__")) {
                        val index = html.indexOf("__NEXT_DATA__")
                        val start = html.indexOf("{", index)
                        if (start != -1) {
                            val extracted = extractJsonFromText(html, start)
                            if (extracted != null) {
                                jsonText = extracted
                                break
                            }
                        }
                    }
                }
            }
            
            if (jsonText.isEmpty()) return null
            val json = JSONObject(jsonText)
            return findProductInJson(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun extractJsonFromText(text: String, startIdx: Int): String? {
        var braceCount = 0
        var foundStart = false
        var endIdx = -1
        for (i in startIdx until text.length) {
            val char = text[i]
            if (char == '{') {
                braceCount++
                foundStart = true
            } else if (char == '}') {
                braceCount--
            }
            if (foundStart && braceCount == 0) {
                endIdx = i
                break
            }
        }
        return if (endIdx != -1) text.substring(startIdx, endIdx + 1) else null
    }

    private fun findProductInJson(json: Any?): PartialMetadata? {
        if (json == null) return null

        if (json is JSONObject) {
            // 1. Try to extract directly from this object if it matches a product
            val directMatch = extractProductFromObj(json)
            if (directMatch != null) {
                return directMatch
            }

            // 2. Prioritize scanning specific keys requested by user
            val priorityKeys = listOf(
                "catalog", "catalogs", "product", "products", "product_details", "productData",
                "catalog_details", "variants", "media", "images", "price", "pricing",
                "supplier", "rating", "reviews"
            )
            for (key in priorityKeys) {
                if (json.has(key)) {
                    val value = json.opt(key)
                    if (value is JSONObject || value is JSONArray) {
                        val res = findProductInJson(value)
                        if (res != null) return res
                    }
                }
            }

            // 3. Fallback to scanning all other keys recursively
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (priorityKeys.contains(key)) continue // Already scanned
                val value = json.opt(key)
                if (value is JSONObject || value is JSONArray) {
                    val res = findProductInJson(value)
                    if (res != null) return res
                }
            }
        } else if (json is JSONArray) {
            for (i in 0 until json.length()) {
                val item = json.opt(i)
                val res = findProductInJson(item)
                if (res != null) return res
            }
        }
        return null
    }

    private fun extractProductFromObj(json: JSONObject): PartialMetadata? {
        val nameKeys = listOf(
            "name", "title", "product_name", "productName", "product_title", "productTitle",
            "product_details_name", "itemName", "displayName", "headline"
        )
        val priceKeys = listOf(
            "price", "original_price", "selling_price", "mrp", "discounted_price", 
            "amount", "price_amount", "priceAmount", "value", "priceVal"
        )
        val imageKeys = listOf(
            "images", "image_url", "imageUrl", "image", "media", "product_image", 
            "productImage", "cover_image", "coverImage", "primary_image", "primaryImage",
            "display_image", "displayImage", "url", "src"
        )
        val ratingKeys = listOf(
            "rating", "rating_value", "ratingValue", "average_rating", "averageRating", 
            "rating_val", "rating_score", "score"
        )

        var foundName: String? = null
        for (key in nameKeys) {
            if (json.has(key)) {
                val candidate = json.optString(key)
                if (candidate != null && candidate.isNotEmpty() && candidate != "null" && candidate.length > 5) {
                    foundName = candidate
                    break
                }
            }
        }

        if (foundName.isNullOrEmpty() || foundName == "null") {
            return null
        }

        // We found a name candidate! Let's extract price recursively from this object
        var foundPrice = findStringValueRecursively(json, priceKeys)
        var foundImage = findStringValueRecursively(json, imageKeys, isImage = true)
        var foundRating = findStringValueRecursively(json, ratingKeys, isRating = true)

        if (foundPrice != null || foundImage != null) {
            // Clean/format price
            var formattedPrice = foundPrice
            if (formattedPrice != null) {
                val cleanPrice = formattedPrice.split('.').firstOrNull() ?: formattedPrice
                val digitsOnly = cleanPrice.filter { it.isDigit() }
                if (digitsOnly.isNotEmpty()) {
                    formattedPrice = "₹$digitsOnly"
                }
            }

            // Convert relative image to absolute
            var absoluteImage = foundImage
            if (absoluteImage != null) {
                if (absoluteImage.startsWith("//")) {
                    absoluteImage = "https:$absoluteImage"
                } else if (absoluteImage.startsWith("/")) {
                    absoluteImage = "https://www.meesho.com$absoluteImage"
                }
            }

            return PartialMetadata(
                title = foundName,
                imageUrl = absoluteImage,
                price = formattedPrice,
                brand = "Meesho",
                rating = foundRating
            )
        }

        return null
    }

    private fun findStringValueRecursively(json: Any?, keys: List<String>, isImage: Boolean = false, isRating: Boolean = false): String? {
        if (json == null) return null
        if (json is JSONObject) {
            for (key in keys) {
                if (json.has(key)) {
                    val value = json.get(key)
                    if (value is JSONArray && value.length() > 0) {
                        if (isImage) {
                            val first = value.opt(0)
                            if (first is JSONObject) {
                                val url = first.optString("url").takeIf { it.isNotEmpty() }
                                    ?: first.optString("src").takeIf { it.isNotEmpty() }
                                    ?: first.optString("image_url")
                                if (!url.isNullOrEmpty()) return url
                            } else {
                                val url = first.toString().trim()
                                if (url.isNotEmpty() && url != "null") return url
                            }
                        } else {
                            val str = value.opt(0).toString().trim()
                            if (str.isNotEmpty() && str != "null") return str
                        }
                    } else if (value is JSONObject) {
                        if (isImage) {
                            val url = value.optString("url").takeIf { it.isNotEmpty() }
                                ?: value.optString("src").takeIf { it.isNotEmpty() }
                                ?: value.optString("image_url")
                            if (!url.isNullOrEmpty()) return url
                        } else {
                            val str = value.toString().trim()
                            if (str.isNotEmpty() && str != "null") return str
                        }
                    } else {
                        val str = value.toString().trim()
                        if (str.isNotEmpty() && str != "null") {
                            if (isImage) {
                                if (str.startsWith("http") || str.contains("/images/") || str.contains("meesho")) {
                                    return str
                                }
                            } else if (isRating) {
                                val floatVal = str.toFloatOrNull()
                                if (floatVal != null && floatVal >= 1.0f && floatVal <= 5.0f) {
                                    return str
                                }
                            } else {
                                if (str != "0") {
                                    return str
                                }
                            }
                        }
                    }
                }
            }
            val jsonKeys = json.keys()
            while (jsonKeys.hasNext()) {
                val nextKey = jsonKeys.next()
                val nextValue = json.opt(nextKey)
                val res = findStringValueRecursively(nextValue, keys, isImage, isRating)
                if (res != null) return res
            }
        } else if (json is JSONArray) {
            for (i in 0 until json.length()) {
                val res = findStringValueRecursively(json.opt(i), keys, isImage, isRating)
                if (res != null) return res
            }
        }
        return null
    }

    private data class PartialMetadata(
        val title: String?,
        val imageUrl: String?,
        val price: String?,
        val brand: String?,
        val rating: String?
    )
}
