package com.drzewiecki.shitcloud.model;

/**
 * @param libraryBaseUrl root of the apps origin; a draft preview sets it as {@code <base href>} so {@code /_lib/}
 *                       loads from the library mirror
 */
public record Platform(String libraryBaseUrl) {
}
