package io.github.superthom196.matv

import io.github.superthom196.matv.ma.ItemMapping
import io.github.superthom196.matv.ma.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumIndexTest {

    private fun artist(name: String) = ItemMapping(itemId = "artist-$name", provider = "library", name = name)

    private fun album(
        id: String,
        title: String,
        artistName: String? = null,
        year: Int? = null,
        sortName: String? = null,
        provider: String = "library",
    ) = MediaItem(
        itemId = id,
        provider = provider,
        name = title,
        sortName = sortName,
        year = year,
        artists = artistName?.let { listOf(artist(it)) },
    )

    // foldKey

    @Test
    fun foldKey_lowercasesAndStripsAccents() {
        assertEquals("bjork", foldKey("Björk"))
        assertEquals("olafur arnalds", foldKey("Ólafur Arnalds"))
    }

    @Test
    fun foldKey_removesPunctuation() {
        assertEquals("acdc", foldKey("AC/DC"))
        assertEquals("sigur ros", foldKey("Sigur Rós"))
        assertEquals("ok computer", foldKey("OK Computer!"))
    }

    @Test
    fun foldKey_collapsesAndTrimsWhitespace() {
        assertEquals("the velvet underground", foldKey("  The   Velvet    Underground  "))
        // Tabs and newlines are whitespace too, not punctuation to delete.
        assertEquals("velvet underground", foldKey("Velvet\tUnderground"))
        assertEquals("velvet underground", foldKey("Velvet\n Underground"))
    }

    @Test
    fun foldKey_keepsDigits() {
        assertEquals("1999", foldKey("1999"))
        assertEquals("2pac", foldKey("2Pac"))
    }

    @Test
    fun foldKey_blankGivesEmpty() {
        assertEquals("", foldKey(""))
        assertEquals("", foldKey("   "))
    }

    // artistSortKey

    @Test
    fun artistSortKey_dropsLeadingThe() {
        assertEquals("beatles", artistSortKey(album("1", "Abbey Road", artistName = "The Beatles")))
    }

    @Test
    fun artistSortKey_doesNotMangleTheAloneOrTheatre() {
        assertEquals("the", artistSortKey(album("1", "X", artistName = "The")))
        assertEquals("theatre", artistSortKey(album("2", "X", artistName = "Theatre")))
    }

    @Test
    fun artistSortKey_usesFirstArtistWithNonBlankName() {
        val item = MediaItem(
            itemId = "1", provider = "library", name = "X",
            artists = listOf(artist(""), artist("   "), artist("Radiohead"), artist("Thom Yorke")),
        )
        assertEquals("radiohead", artistSortKey(item))
    }

    @Test
    fun artistSortKey_noArtistsGivesEmpty() {
        assertEquals("", artistSortKey(album("1", "X", artistName = null)))
        assertEquals("", artistSortKey(MediaItem(itemId = "2", provider = "library", name = "X", artists = emptyList())))
    }

    // artistNameKey

    @Test
    fun artistNameKey_prefersSortNameOverName() {
        val item = MediaItem(itemId = "1", provider = "library", name = "The Beatles", sortName = "Beatles, The")
        assertEquals("beatles the", artistNameKey(item))
    }

    @Test
    fun artistNameKey_fallsBackToNameAndDropsThe() {
        val item = MediaItem(itemId = "1", provider = "library", name = "The National")
        assertEquals("national", artistNameKey(item))
    }

    // bucketOf / rankOf

    @Test
    fun bucketOf_lettersMapToUppercase() {
        assertEquals('A', bucketOf("abba"))
        assertEquals('Z', bucketOf("zz top"))
    }

    @Test
    fun bucketOf_digitsAndBlankGoToHash() {
        assertEquals('#', bucketOf("1999"))
        assertEquals('#', bucketOf(""))
    }

    @Test
    fun rankOf_hashSortsAfterZ() {
        assertEquals(1, rankOf("A"))
        assertEquals(26, rankOf("Z"))
        assertEquals(27, rankOf("#"))
        assertTrue(rankOf("#") > rankOf("Z"))
    }

    // buildAlbumIndex

    private val mixed = listOf(
        album("b2", "Revolver", artistName = "The Beatles", year = 1966),
        album("z1", "Eliminator", artistName = "ZZ Top", year = 1983),
        album("n1", "1999", artistName = "1999 Collective", year = 1999),
        album("a1", "Arrival", artistName = "ABBA", year = 1976),
        album("b1", "Abbey Road", artistName = "The Beatles", year = 1969),
        album("b3", "Live", artistName = "The Beatles", year = 1977),
        album("b4", "Live", artistName = "The Beatles", year = 1962),
        album("u1", "Untitled", artistName = null),
        album("bj", "Debut", artistName = "Björk", year = 1993),
    )

    @Test
    fun buildAlbumIndex_sortsByArtistThenTitleThenYear() {
        val (items, _) = buildAlbumIndex(mixed)
        assertEquals(
            listOf("a1", "b1", "b4", "b3", "b2", "bj", "z1", "u1", "n1"),
            items.map { it.itemId },
        )
    }

    @Test
    fun buildAlbumIndex_hashItemsLandLast() {
        val (items, anchors) = buildAlbumIndex(mixed)
        assertEquals("#", anchors.last().label)
        assertEquals(setOf("n1", "u1"), items.takeLast(2).map { it.itemId }.toSet())
    }

    @Test
    fun buildAlbumIndex_anchorsHaveLabelIndexCount() {
        val (_, anchors) = buildAlbumIndex(mixed)
        assertEquals(
            listOf(
                LetterAnchor("A", 0, 1),
                LetterAnchor("B", 1, 5),
                LetterAnchor("Z", 6, 1),
                LetterAnchor("#", 7, 2),
            ),
            anchors,
        )
    }

    @Test
    fun buildAlbumIndex_anchorsAreContiguousAndCoverAllItems() {
        val (items, anchors) = buildAlbumIndex(mixed)
        var expected = 0
        for (a in anchors) {
            assertEquals(expected, a.index)
            assertTrue(a.count > 0)
            expected += a.count
        }
        assertEquals(items.size, expected)
        assertEquals(anchors.map { it.label }, anchors.map { it.label }.distinct())
    }

    @Test
    fun buildAlbumIndex_titleUsesSortNameWhenPresent() {
        val (items, _) = buildAlbumIndex(
            listOf(
                album("x2", "The Wall", artistName = "Pink Floyd", sortName = "Wall, The"),
                album("x1", "Animals", artistName = "Pink Floyd"),
            ),
        )
        assertEquals(listOf("x1", "x2"), items.map { it.itemId })
    }

    @Test
    fun buildAlbumIndex_tieBreaksOnProviderThenItemId() {
        val (items, _) = buildAlbumIndex(
            listOf(
                album("2", "Same", artistName = "Same", year = 2000, provider = "library"),
                album("1", "Same", artistName = "Same", year = 2000, provider = "spotify"),
                album("1", "Same", artistName = "Same", year = 2000, provider = "library"),
            ),
        )
        assertEquals(listOf("library:1", "library:2", "spotify:1"), items.map { "${it.provider}:${it.itemId}" })
    }

    @Test
    fun buildAlbumIndex_missingYearSortsAfterKnownYear() {
        val (items, _) = buildAlbumIndex(
            listOf(
                album("noyear", "Same", artistName = "Same", year = null),
                album("year", "Same", artistName = "Same", year = 2001),
            ),
        )
        assertEquals(listOf("year", "noyear"), items.map { it.itemId })
    }

    @Test
    fun buildAlbumIndex_customSortKeyUsedForBuckets() {
        val artists = listOf(
            MediaItem(itemId = "1", provider = "library", name = "The Who"),
            MediaItem(itemId = "2", provider = "library", name = "Adele"),
        )
        val (items, anchors) = buildAlbumIndex(artists, ::artistNameKey)
        assertEquals(listOf("2", "1"), items.map { it.itemId })
        assertEquals(listOf(LetterAnchor("A", 0, 1), LetterAnchor("W", 1, 1)), anchors)
    }

    @Test
    fun buildAlbumIndex_singleItem() {
        val (items, anchors) = buildAlbumIndex(listOf(album("only", "Only", artistName = "Miles Davis")))
        assertEquals(1, items.size)
        assertEquals(listOf(LetterAnchor("M", 0, 1)), anchors)
    }

    @Test
    fun buildAlbumIndex_emptyListGivesEmptyAnchors() {
        val (items, anchors) = buildAlbumIndex(emptyList())
        assertTrue(items.isEmpty())
        assertTrue(anchors.isEmpty())
    }

    // indexForLetter

    private val anchors = listOf(
        LetterAnchor("A", 0, 3),
        LetterAnchor("C", 3, 2),
        LetterAnchor("M", 5, 4),
        LetterAnchor("#", 9, 1),
    )
    private val itemCount = 10

    @Test
    fun indexForLetter_exactLetter() {
        assertEquals(0, indexForLetter(anchors, "A"))
        assertEquals(3, indexForLetter(anchors, "C"))
        assertEquals(5, indexForLetter(anchors, "M"))
    }

    @Test
    fun indexForLetter_emptyLetterLandsOnNextPopulated() {
        assertEquals(3, indexForLetter(anchors, "B"))
        assertEquals(5, indexForLetter(anchors, "D"))
        assertEquals(5, indexForLetter(anchors, "L"))
    }

    @Test
    fun indexForLetter_pastLastPopulatedLetterReturnsItemsSize() {
        val noHash = anchors.dropLast(1)
        assertEquals(9, indexForLetter(noHash, "N"))
        assertEquals(9, indexForLetter(noHash, "Z"))
        assertEquals(9, indexForLetter(noHash, "#"))
    }

    @Test
    fun indexForLetter_hashAndLettersAfterLastRunLandOnHash() {
        assertEquals(9, indexForLetter(anchors, "#"))
        assertEquals(9, indexForLetter(anchors, "Z"))
        assertEquals(itemCount, anchors.last().index + anchors.last().count)
    }

    @Test
    fun indexForLetter_emptyAnchorsGivesZero() {
        assertEquals(0, indexForLetter(emptyList(), "A"))
        assertEquals(0, indexForLetter(emptyList(), "#"))
    }

    // labelAtIndex

    @Test
    fun labelAtIndex_insideRun() {
        assertEquals("A", labelAtIndex(anchors, 2))
        assertEquals("C", labelAtIndex(anchors, 4))
        assertEquals("M", labelAtIndex(anchors, 5))
        assertEquals("M", labelAtIndex(anchors, 8))
    }

    @Test
    fun labelAtIndex_zero() {
        assertEquals("A", labelAtIndex(anchors, 0))
    }

    @Test
    fun labelAtIndex_beyondLastAnchorReturnsLastLabel() {
        assertEquals("#", labelAtIndex(anchors, 9))
        assertEquals("#", labelAtIndex(anchors, 500))
    }

    @Test
    fun labelAtIndex_beforeFirstAnchorReturnsFirstLabel() {
        val offset = listOf(LetterAnchor("C", 2, 1))
        assertEquals("C", labelAtIndex(offset, 0))
    }

    @Test
    fun labelAtIndex_emptyAnchorsReturnsA() {
        assertEquals("A", labelAtIndex(emptyList(), 0))
        assertEquals("A", labelAtIndex(emptyList(), 7))
    }
}
