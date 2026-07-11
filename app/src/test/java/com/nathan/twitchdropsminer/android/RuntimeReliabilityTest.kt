package com.nathan.twitchdropsminer.android

import com.nathan.twitchdropsminer.android.data.model.Channel
import com.nathan.twitchdropsminer.android.runtime.EligibleChannelSelector
import com.nathan.twitchdropsminer.android.runtime.ChannelPickerSelection
import com.nathan.twitchdropsminer.android.runtime.RuntimeRetryBackoff
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeReliabilityTest {
    @Test
    fun retryBackoffGrowsExponentiallyAndCapsAtFiveMinutes() {
        assertEquals(Duration.ofSeconds(15), RuntimeRetryBackoff.delayFor(1))
        assertEquals(Duration.ofSeconds(30), RuntimeRetryBackoff.delayFor(2))
        assertEquals(Duration.ofMinutes(1), RuntimeRetryBackoff.delayFor(3))
        assertEquals(Duration.ofMinutes(2), RuntimeRetryBackoff.delayFor(4))
        assertEquals(Duration.ofMinutes(4), RuntimeRetryBackoff.delayFor(5))
        assertEquals(Duration.ofMinutes(5), RuntimeRetryBackoff.delayFor(6))
        assertEquals(Duration.ofMinutes(5), RuntimeRetryBackoff.delayFor(50))
    }

    @Test
    fun channelSelectionPrefersAclEligibilityThenViewerCount() {
        val directory = eligibleChannel(id = 1, name = "large", viewers = 50_000)
        val smallerAcl = eligibleChannel(
            id = 2,
            name = "allowed",
            viewers = 100,
            aclBased = true,
        )

        val selected = EligibleChannelSelector.select(
            channels = listOf(directory, smallerAcl),
            skippedChannelIds = emptySet(),
        )

        assertEquals(2L, selected?.id)
    }

    @Test
    fun channelSelectionSkipsCooledDownAndIncompleteStreams() {
        val cooledDown = eligibleChannel(id = 1, name = "cooled", viewers = 50_000)
        val noBroadcast = eligibleChannel(id = 2, name = "missing-broadcast", viewers = 10_000)
            .copy(broadcastId = null)
        val offline = eligibleChannel(id = 3, name = "offline", viewers = 1_000)
            .copy(online = false)
        val fallback = eligibleChannel(id = 4, name = "healthy", viewers = 500)

        val selected = EligibleChannelSelector.select(
            channels = listOf(cooledDown, noBroadcast, offline, fallback),
            skippedChannelIds = setOf(1L),
        )

        assertEquals(4L, selected?.id)
    }

    @Test
    fun channelSelectionReturnsNullWithoutAWatchableStream() {
        val invalid = eligibleChannel(id = 0, name = "invalid", viewers = 100)
            .copy(dropsEnabled = false, broadcastId = null)

        assertNull(EligibleChannelSelector.select(listOf(invalid), emptySet()))
    }

    @Test
    fun pickerSelectionReturnsTheRequestedCompatibleChannel() {
        val original = eligibleChannel(id = 1, name = "original", viewers = 5_000)
        val replacement = eligibleChannel(id = 2, name = "replacement", viewers = 2_000)

        val selected = ChannelPickerSelection.findCompatibleChannel(
            channels = listOf(original, replacement),
            channelId = replacement.id,
        )

        assertEquals(2L, selected?.id)
    }

    @Test
    fun pickerSelectionRejectsAChannelThatIsNoLongerCompatible() {
        val original = eligibleChannel(id = 1, name = "original", viewers = 5_000)
        val unavailable = eligibleChannel(id = 2, name = "offline", viewers = 2_000)
            .copy(online = false)

        val selected = ChannelPickerSelection.findCompatibleChannel(
            channels = listOf(original, unavailable),
            channelId = unavailable.id,
        )

        assertNull(selected)
    }
}

private fun eligibleChannel(
    id: Long,
    name: String,
    viewers: Int,
    aclBased: Boolean = false,
): Channel = Channel(
    id = id,
    name = name,
    viewers = viewers,
    online = true,
    dropsEnabled = true,
    aclBased = aclBased,
    broadcastId = "broadcast-$id",
)
