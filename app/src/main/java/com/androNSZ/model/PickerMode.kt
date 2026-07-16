package com.androNSZ.model

/**
 * What the in-app file picker lets the user mark.
 *
 * - [FilesOnly]: multi-select files; folders are navigation-only (used by the
 *   file-queue mode).
 * - [FoldersOnly]: single-select one folder; files are shown for context but not
 *   selectable (used by the folder mode, whose pipeline is single-root).
 * - [FilesAndFolders]: multi-select files AND folders together; folders stay
 *   navigable. Used by the future combined mode (GUI only for now — no unpack
 *   pipeline wired yet).
 */
enum class PickerMode {
   FilesOnly,
   FoldersOnly,
   FilesAndFolders
}
