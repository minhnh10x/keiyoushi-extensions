package eu.kanade.tachiyomi.extension.vi.daomeoden

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class DaoMeoDen : KeiSource() {
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3)

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${normalizeMangaPath(manga.url)}"

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url =
            "$baseUrl/advanced-search"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("sort", "views")
                .addQueryParameter("order", "views")
                .addQueryParameter("page", page.toString())
                .build()

        val result = parseBrowsePage(client.get(url), page)
        if (result.mangas.isNotEmpty() || page > 1) return result

        // Fallback về trang chủ nếu trang tìm kiếm nâng cao trả về rỗng ở trang 1
        return parseBrowsePage(client.get("$baseUrl/?typeHome=star".toHttpUrl()), page, isHomeFallback = true)
    }

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url =
            "$baseUrl/advanced-search"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("sort", "updated_at")
                .addQueryParameter("order", "updated_at")
                .addQueryParameter("page", page.toString())
                .build()

        val result = parseBrowsePage(client.get(url), page)
        if (result.mangas.isNotEmpty() || page > 1) return result

        return parseBrowsePage(client.get("$baseUrl/webcomic?page=$page".toHttpUrl()), page)
    }

    // ============================== Search ================================

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val section = filters.firstInstanceOrNull<SectionFilter>()?.toUriPart().orEmpty()
        val status = filters.firstInstanceOrNull<StatusFilter>()?.toUriPart().orEmpty()
        val genre = filters.firstInstanceOrNull<GenreFilter>()?.toUriPart().orEmpty()
        val tag = filters.firstInstanceOrNull<TagFilter>()?.toUriPart().orEmpty()
        val sort = filters.firstInstanceOrNull<SortFilter>()?.toUriPart().orEmpty()

        val targetPath =
            if (section.isNotEmpty() && query.isEmpty() && genre.isEmpty() && tag.isEmpty() && status.isEmpty()) {
                section
            } else {
                "/advanced-search"
            }

        val url =
            "$baseUrl$targetPath"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("page", page.toString())
                .apply {
                    if (query.isNotBlank()) {
                        addQueryParameter("q", query.trim())
                    }
                    if (status.isNotEmpty()) {
                        addQueryParameter("status", status)
                    }
                    if (genre.isNotEmpty()) {
                        addQueryParameter("genre", genre)
                    }
                    if (tag.isNotEmpty()) {
                        addQueryParameter("tag", tag)
                    }
                    if (sort.isNotEmpty()) {
                        addQueryParameter("sort", sort)
                        addQueryParameter("order", sort)
                    }
                }.build()

        return parseBrowsePage(client.get(url), page)
    }

    // ============================== Manga List ============================

    private fun parseBrowsePage(
        response: Response,
        page: Int,
        isHomeFallback: Boolean = false,
    ): MangasPage {
        val document = response.asJsoup()

        // 1. Lấy danh sách truyện từ DOM (card truyện)
        val domMangas =
            document
                .select("main div.group:has(a[href*=/truyen-tranh/]), main a.group[href*=/truyen-tranh/]")
                .mapNotNull { mangaFromElement(it) }
                .distinctBy { it.url }

        // 2. Dự phòng trích xuất từ payload JSON của Next.js RSC (self.__next_f.push)
        val mangas =
            domMangas.ifEmpty {
                parseMangasFromNextData(document.html())
            }

        if (isHomeFallback) {
            return MangasPage(mangas, false)
        }

        val hasNextPage = hasNextPage(document, page, mangas.size)
        return MangasPage(mangas, hasNextPage)
    }

    private fun mangaFromElement(element: Element): SManga? {
        val linkElement =
            if (element.tagName() == "a" && element.attr("href").contains("/truyen-tranh/")) {
                element
            } else {
                element.selectFirst("a[title][href*=/truyen-tranh/]")
                    ?: element.selectFirst("a[href*=/truyen-tranh/]")
            } ?: return null

        val rawHref = linkElement.absUrl("href").takeIf { it.isNotBlank() } ?: return null
        val imgElement = element.selectFirst("img")
        val parsedTitle =
            element
                .selectFirst("h3, h4")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: linkElement.attr("title").trim().takeIf { it.isNotEmpty() }
                ?: imgElement?.attr("alt")?.trim()
                ?: return null

        return SManga.create().apply {
            setUrlWithoutDomain(normalizeMangaPath(rawHref))
            title = parsedTitle
            thumbnail_url =
                imgElement
                    ?.let {
                        it.absUrl("data-src").ifEmpty { it.absUrl("src") }
                    }?.normalizeImageUrl()
        }
    }

    private fun hasNextPage(
        document: Document,
        currentPage: Int,
        itemCount: Int,
    ): Boolean {
        // Kiểm tra link hoặc nút chuyển sang trang kế tiếp
        if (document.selectFirst("a[href*=page=${currentPage + 1}]") != null) {
            return true
        }
        val nextBtn =
            document.select("a, button").firstOrNull {
                it.text().trim().equals("Sau", ignoreCase = true)
            }
        if (nextBtn != null && !nextBtn.hasAttr("disabled")) {
            return true
        }

        // Kiểm tra text dạng "Trang 1/367"
        val pageInfoMatch = pageRatioRegex.find(document.text())
        if (pageInfoMatch != null) {
            val cur = pageInfoMatch.groupValues[1].toIntOrNull() ?: currentPage
            val total = pageInfoMatch.groupValues[2].toIntOrNull() ?: currentPage
            return cur < total
        }

        return itemCount >= 24
    }

    // ============================== Details ===============================

    private fun parseMangaDetails(
        document: Document,
        manga: SManga,
    ): SManga = SManga.create().apply {
        setUrlWithoutDomain(normalizeMangaPath(manga.url))

        val nextManga = parseSingleMangaFromNextData(document.html(), url.substringAfterLast("-"))

        title = document
            .selectFirst("main h1")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: document
                .selectFirst("meta[property=og:title]")
                ?.attr("content")
                ?.substringBeforeLast(" - Đảo Mèo")
                ?.trim()
            ?: nextManga?.title
            ?: manga.title

        thumbnail_url = document
            .selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.normalizeImageUrl()
            ?: document.selectFirst("main div.aspect-\\[3\\/4\\] img")?.absUrl("src")?.normalizeImageUrl()
            ?: nextManga?.coverUrl?.normalizeImageUrl()

        author = document
            .selectFirst("main a[href*=/nhom-dich/]")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: nextManga?.author?.takeUnless { it.equals("Đang cập nhật", ignoreCase = true) }

        val badgeSpans = document.select("main h1 + div > span").map { it.text().trim() }
        status = badgeSpans.firstNotNullOfOrNull { text ->
            parseStatus(text).takeIf { it != SManga.UNKNOWN }
        } ?: parseStatus(nextManga?.status)

        val genres =
            buildList {
                // Loại truyện (Manhwa / Manga / Manhua)
                document
                    .selectFirst("main h1 + div > span.text-violet-300")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { add(it) }

                // Thể loại & Tag
                document
                    .select("main a[href*=/advanced-search?genre=], main a[href*=/advanced-search?tag=]")
                    .mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
                    .forEach { add(it) }

                if (isEmpty() && nextManga != null) {
                    addAll(nextManga.genre)
                    addAll(nextManga.tags)
                }
            }.distinct()

        genre = genres.joinToString().ifEmpty { null }

        description = document
            .selectFirst("main h3:contains(GIỚI THIỆU) + p, main h3:contains(GIỚI THIỆU) ~ div")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: nextManga?.description?.takeIf { !it.startsWith("$") }
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
    }

    private fun parseStatus(statusText: String?): Int = when (val s = statusText?.lowercase()) {
        null -> {
            SManga.UNKNOWN
        }

        else -> {
            when {
                s.contains("ongoing") || s.contains("đang tiến hành") || s.contains("đang ra") -> SManga.ONGOING
                s.contains("completed") || s.contains("full") || s.contains("hoàn") -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null

        val mangaPath =
            when (url.pathSegments.firstOrNull()) {
                "truyen-tranh" -> {
                    normalizeMangaPath(url.encodedPath)
                }

                "doc-truyen-tranh" -> {
                    val mangaSlug = url.pathSegments.getOrNull(1) ?: return null
                    "/truyen-tranh/${normalizeMangaPath(mangaSlug)}"
                }

                else -> {
                    return null
                }
            }
        val manga = SManga.create().apply { setUrlWithoutDomain(mangaPath) }

        return fetchMangaUpdate(manga, emptyList(), true, false).manga.apply {
            initialized = true
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        return SMangaUpdate(
            manga = parseMangaDetails(document, manga),
            chapters = parseChapterList(document),
        )
    }

    // ============================== Chapters ==============================

    private fun parseChapterList(document: Document): List<SChapter> {
        val chapterElements =
            document
                .select("main a[data-chapter-number][href*=/doc-truyen-tranh/]")
                .ifEmpty {
                    document.select("main div.grid > a[href*=/doc-truyen-tranh/]")
                }

        return chapterElements.mapNotNull { element ->
            val chapterUrl =
                element.absUrl("href").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

            val mainLabel = element.selectFirst("div > span")?.text()?.trim()
            val subLabel = element.selectFirst("span.truncate")?.text()?.trim()
            val dateText = element.selectFirst("div:has(svg) > span, div.mt-1 > span")?.text()?.trim()

            SChapter.create().apply {
                setUrlWithoutDomain(chapterUrl)
                name = subLabel?.takeIf { it.isNotEmpty() }
                    ?: mainLabel?.takeIf { it.isNotEmpty() }
                    ?: element.text().trim()
                date_upload = parseChapterDate(dateText)
                val chapNum =
                    element.attr("data-chapter-number").toFloatOrNull()
                        ?: chapterNumberRegex.find(name)?.value?.toFloatOrNull()
                if (chapNum != null) {
                    chapter_number = chapNum
                }
            }
        }
    }

    private fun parseChapterDate(date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        return runCatching {
            LocalDateTime
                .parse(date.trim(), chapterDateFormat)
                .atZone(chapterDateZone)
                .toInstant()
                .toEpochMilli()
        }.getOrDefault(0L)
    }

    // ============================== Pages =================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get(getChapterUrl(chapter))
        val chapterUrl = response.request.url.toString()
        val document = response.asJsoup()

        val imageElements =
            document
                .select("main main img, main div.space-y-0 img")
                .ifEmpty {
                    document.select("main img[alt*='Trang '], main img[src*='/books/']")
                }

        val pages =
            imageElements
                .mapIndexedNotNull { index, element ->
                    val imageUrl =
                        element
                            .absUrl("data-src")
                            .ifEmpty { element.absUrl("src") }
                            .normalizeImageUrl()

                    if (imageUrl.isEmpty() || imageUrl.contains("1mind.gif")) {
                        return@mapIndexedNotNull null
                    }

                    Page(index, url = chapterUrl, imageUrl = imageUrl)
                }.distinctBy { it.imageUrl }

        if (pages.isNotEmpty()) return pages

        // Fallback trích xuất từ script Next.js nếu ảnh nằm trong RSC payload
        return extractChapterImagesFromNextData(document.html()).mapIndexed { index, imgUrl ->
            Page(index, url = chapterUrl, imageUrl = imgUrl)
        }
    }

    override fun imageRequest(page: Page): Request {
        // Trang đọc sử dụng referrerpolicy="no-referrer" trên các thẻ <img>
        val imageHeaders =
            headers
                .newBuilder()
                .removeAll("Referer")
                .build()
        return GET(page.imageUrl!!, imageHeaders)
    }

    // ============================== Filters ===============================

    override fun getFilterList(): FilterList = getFilters()

    // ============================== Related ===============================

    override val supportsRelatedMangas get() = true

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        val normalizedCurrentUrl = normalizeMangaPath(manga.url)

        val relatedSection =
            document
                .select("main section")
                .firstOrNull { section ->
                    section.selectFirst("h3")?.text()?.contains("Truyện đề xuất", ignoreCase = true) == true
                }

        val elements =
            relatedSection?.select("div.group:has(a[href*=/truyen-tranh/])")
                ?: emptyList()

        return elements
            .mapNotNull { mangaFromElement(it) }
            .filterNot { normalizeMangaPath(it.url) == normalizedCurrentUrl }
            .distinctBy { it.url }
    }

    // ============================== Helpers ===============================

    private fun normalizeMangaPath(path: String): String = path.removeSuffix("-0.html").removeSuffix(".html")

    private fun String.normalizeImageUrl(): String = when {
        startsWith("//") -> "https:$this"
        startsWith("/storage/") -> "$defaultImageDomain${removePrefix("/storage")}"
        else -> this
    }

    private fun unescapeNextScripts(html: String): String = nextPushRegex.findAll(html).joinToString("\n") { match ->
        match.groupValues[1]
            .replace("""\\"""", "\"")
            .replace("""\\n""", "\n")
            .replace("""\\r""", "\r")
            .replace("""\\/""", "/")
            .replace("""\\\\""", "\\")
    }

    private fun parseMangasFromNextData(html: String): List<SManga> {
        val unescaped = unescapeNextScripts(html)
        return nextMangaObjectRegex
            .findAll(unescaped)
            .mapNotNull { match ->
                runCatching {
                    val dto = match.value.parseAs<NextMangaDto>()
                    SManga.create().apply {
                        setUrlWithoutDomain("/truyen-tranh/${dto.slug}-${dto.id}")
                        title = dto.title
                        thumbnail_url =
                            (dto.coverUrl ?: dto.images?.coverUrl ?: dto.images?.cover)
                                ?.normalizeImageUrl()
                    }
                }.getOrNull()
            }.distinctBy { it.url }
            .toList()
    }

    private fun parseSingleMangaFromNextData(
        html: String,
        targetId: String,
    ): NextMangaDto? {
        val unescaped = unescapeNextScripts(html)
        return nextMangaObjectRegex
            .findAll(unescaped)
            .mapNotNull { match -> runCatching { match.value.parseAs<NextMangaDto>() }.getOrNull() }
            .firstOrNull { it.id.toString() == targetId }
    }

    private fun extractChapterImagesFromNextData(html: String): List<String> {
        val unescaped = unescapeNextScripts(html)
        return chapterImgRegex
            .findAll(unescaped)
            .map { it.value.normalizeImageUrl() }
            .filterNot { it.contains("/cover_") || it.contains("/poster_") }
            .distinct()
            .toList()
    }

    @Serializable
    private class NextMangaDto(
        val id: Int,
        val slug: String,
        val title: String,
        val coverUrl: String? = null,
        val images: NextMangaImagesDto? = null,
        val genre: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val status: String? = null,
        val description: String? = null,
        val author: String? = null,
    )

    @Serializable
    private class NextMangaImagesDto(
        val cover: String? = null,
        @SerialName("cover_url")
        val coverUrl: String? = null,
    )

    private val defaultImageDomain = "https://dmd-image-content-sng-static-0.imggo.net"
    private val chapterNumberRegex = Regex("""\d+(?:\.\d+)?""")
    private val pageRatioRegex = Regex("""Trang\s+(\d+)\s*/\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val nextPushRegex = Regex("""self\.__next_f\.push\(\[1,"((?:\\.|[^"\\])*)"\]\)""")
    private val nextMangaObjectRegex = Regex("""\{"id":\d+,"slug":"[^"]+","title":"[^"]+",.*?"isAdult":(?:true|false).*?\}""")
    private val chapterImgRegex = Regex("""https?://[^"'\s\\]+/books/[^"'\s\\]+\.(?:webp|jpg|jpeg|png)""", RegexOption.IGNORE_CASE)
    private val chapterDateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy - HH:mm", Locale.ROOT)
    private val chapterDateZone = ZoneId.of("Asia/Ho_Chi_Minh")
}
