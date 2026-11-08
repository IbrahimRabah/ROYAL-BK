package com.velora.api.marketing.domain;

public enum AnnouncementAnimation {
    /** Still text. Also what a visitor who asks for reduced motion gets. */
    STATIC,
    /** The text scrolls across the bar. */
    MARQUEE,
    /** The text fades in, and several messages take turns. */
    FADE,
    /** The text gently pulses to draw the eye. */
    PULSE
}
