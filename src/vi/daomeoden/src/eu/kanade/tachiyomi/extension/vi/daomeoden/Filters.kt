package eu.kanade.tachiyomi.extension.vi.daomeoden

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

fun getFilters(): FilterList = FilterList(
    SectionFilter(),
    StatusFilter(),
    GenreFilter(),
    TagFilter(),
    SortFilter(),
)

class SectionFilter :
    UriPartFilter(
        "Chuyên mục",
        arrayOf(
            Pair("Tất cả (Tìm kiếm nâng cao)", ""),
            Pair("Truyện Webcomic", "/webcomic"),
            Pair("Truyện Hành Động", "/action"),
            Pair("Truyện LGBT / Đam Mỹ", "/lgbt"),
            Pair("Truyện NSFW (18+)", "/18-plus"),
        ),
    )

class StatusFilter :
    UriPartFilter(
        "Trạng thái",
        arrayOf(
            Pair("Tất cả", ""),
            Pair("Đang tiến hành", "ongoing"),
            Pair("Hoàn thành (Full)", "completed"),
        ),
    )

class GenreFilter :
    UriPartFilter(
        "Thể loại",
        arrayOf(
            Pair("Tất cả", ""),
            Pair("Manhwa", "Manhwa"),
            Pair("Manga", "Manga"),
            Pair("Manhua", "Manhua"),
            Pair("Action", "Action"),
            Pair("Adventure", "Adventure"),
            Pair("Comedy", "Comedy"),
            Pair("Drama", "Drama"),
            Pair("Ecchi", "Ecchi"),
            Pair("Fantasy", "Fantasy"),
            Pair("Hentai", "Hentai"),
            Pair("Historical", "Historical"),
            Pair("Horror", "Horror"),
            Pair("Isekai", "Isekai"),
            Pair("Martial Arts", "Martial Arts"),
            Pair("Mystery", "Mystery"),
            Pair("Oneshot", "Oneshot"),
            Pair("Psychological", "Psychological"),
            Pair("Romance", "Romance"),
            Pair("Sci-fi", "Sci-fi"),
            Pair("Slice of life", "Slice of life"),
            Pair("Thriller", "Thriller"),
            Pair("Tragedy", "Tragedy"),
        ),
    )

class TagFilter :
    UriPartFilter(
        "Nhãn (Tag)",
        arrayOf(
            Pair("Tất cả", ""),
            Pair("Ngôn Tình", "Ngôn Tình"),
            Pair("Lãng mạn", "Lãng mạn"),
            Pair("Tình Cảm", "Tình Cảm"),
            Pair("Học Đường", "Học Đường"),
            Pair("Thanh xuân vườn trường", "thanh xuân vườn trường"),
            Pair("Hiện Đại", "Hiện Đại"),
            Pair("Cổ Trang", "Cổ Trang"),
            Pair("Âu Cổ", "Âu Cổ"),
            Pair("Chuyển Sinh", "Chuyển Sinh"),
            Pair("Xuyên Không", "Xuyên Không"),
            Pair("Trùng Sinh", "Trùng Sinh"),
            Pair("Hài Hước", "Hài Hước"),
            Pair("Huyền Ảo", "Huyền Ảo"),
            Pair("Phiêu Lưu", "Phiêu Lưu"),
            Pair("Nữ Cường", "Nữ Cường"),
            Pair("Harem", "Harem"),
            Pair("Truyện Màu", "Truyện Màu"),
            Pair("18+", "18+"),
        ),
    )

class SortFilter :
    UriPartFilter(
        "Sắp xếp",
        arrayOf(
            Pair("Ngày cập nhật", "updated_at"),
            Pair("Ngày đăng", "created_at"),
            Pair("Lượt xem", "views"),
        ),
    )

open class UriPartFilter(
    displayName: String,
    private val options: Array<Pair<String, String>>,
) : Filter.Select<String>(displayName, options.map { it.first }.toTypedArray()) {
    fun toUriPart(): String = options[state].second
}
