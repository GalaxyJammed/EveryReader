<div align="center">

# EveryReader - Open Any File

**An Android file reader and editor for PDFs, spreadsheets, documents, presentations and eBooks, built with Jetpack Compose**

![Kotlin](https://img.shields.io/badge/Kotlin-100%25-7F52FF?logo=kotlin&logoColor=white)
![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)
![Min SDK](https://img.shields.io/badge/Min%20SDK-29-blue)
![GitHub release](https://img.shields.io/github/v/release/galaxyjammed/EveryReader?logo=github&label=GitHub%20release)
![License](https://img.shields.io/badge/License-MIT-lightgrey)
![Project Status](https://img.shields.io/badge/Project%20Status-In%20Progress-orange)

⭐ **If EveryReader helps you, please star this repository. It helps other users find the project.**

</div>

## What is EveryReader?

EveryReader finds the files on your phone, whatever their type, and opens them in one place. Spreadsheets show as sheets, presentations show as slides, and PDFs and documents show as pages. Where it makes sense, you can also edit: mark up PDFs, change text in documents and spreadsheets, and save the result back to your phone. Everything stays on your device.

<div align="center">
  <img width="100" height="100" alt="app_logo" src="https://github.com/user-attachments/assets/737a7a33-ace7-40ca-8a07-396663a1051b" />
  <br>
  <em>"All files, unlocked"</em>
</div>

## Features

### 📂 Browse and Find Files
- Scans your phone's storage and groups files into categories: **PDF, Text, Excel, PowerPoint, Word and eBooks**
- Sort by **Date Created, Date Updated, Alphabetical or Content Size**
- Every file shows its folder path, such as `Internal Storage > Documents > Reports`
- **Long-press** a file to select several, then **share** or **delete** them together
- Each file has a menu with **Share** and **Delete**
- Dark and light themes, with each category tinted in its own colour

### 📖 Viewing
- **PDF** pages render at full quality, with pinch-to-zoom (1x to 5x), rotation and panning
- **Presentations** show slide by slide, with a slide switcher
- **Documents** show as A4 or Letter pages, with page numbers and zoom
- **Spreadsheets** show Excel-style sheets with column letters, row numbers and gridlines
- **Text files** load in pages of about 100 words, so very large files stay fast
- Large files open without freezing, including files of 20 MB or more
- Scrollbars show where you are and how far you can scroll
- **Combine file paths** to simulate a proper file manager with only the supported files showing in each folder

### ✏️ Editing
- **PDF:** draw in four colours with adjustable width, erase parts of a mark, add text, add signatures, select, move, resize and rotate marks, then undo and redo. Save as a copy or overwrite the original
- **Text files:** edit one page at a time, search with highlighted matches, undo and redo
- **Spreadsheets:** tap a cell to edit it, search the sheet, undo and redo. Empty cells outside your data are editable too
- **Word documents:** change paragraph text, text colour and underline colour, and move, resize and rotate images. Undo and redo are available
- **eBooks (FB2):** change paragraph text, with undo and redo
- Unsaved changes trigger a warning before you leave

## Supported File Types

**Legend:** ✅ View and edit · 👁️ View only · 🚧 Planned

### 📄 PDF

| Format | Description | Mode |
|---|---|---|
| `.pdf` | Portable Document Format | ✅ View and edit |

### 📝 Text

| Format | Description | Mode |
|---|---|---|
| `.txt` | Plain text | ✅ View and edit |

### 📊 Excel (Spreadsheets)

| Format | Description | Mode |
|---|---|---|
| `.xls` | Excel 97–2003 | ✅ View and edit |
| `.xlsx` | Excel 2007+ (Open XML) | ✅ View and edit |
| `.csv` | Comma-separated values | ✅ View and edit |
| `.tsv` | Tab-separated values | ✅ View and edit |
| `.psv` | Pipe-separated values | ✅ View and edit |
| `.ssv` | Semicolon-separated values | ✅ View and edit |
| `.xlsm` | Excel macro-enabled | 👁️ View only |
| `.xltx`, `.xltm` | Excel templates | 👁️ View only |
| `.ods`, `.fods` | OpenDocument Spreadsheet | 👁️ View only |

### 📽️ PowerPoint (Presentations)

| Format | Description | Mode |
|---|---|---|
| `.pptx` | PowerPoint 2007+ (Open XML) | 👁️ View only, approximate layout |
| `.pptm` | PowerPoint macro-enabled | 👁️ View only, approximate layout |
| `.potx`, `.potm` | PowerPoint templates | 👁️ View only, approximate layout |
| `.ppsx`, `.ppsm` | PowerPoint slide shows | 👁️ View only, approximate layout |
| `.odp`, `.otp` | OpenDocument Presentation | 👁️ View only, approximate layout |
| `.ppt` | PowerPoint 97–2003 | 🚧 Planned |

### 📃 Word (Documents)

| Format | Description | Mode |
|---|---|---|
| `.docx` | Word 2007+ (Open XML) | ✅ View and edit |
| `.docm` | Word macro-enabled | ✅ View and edit (macros are kept) |
| `.dotx`, `.dotm` | Word templates | ✅ View and edit |
| `.odt`, `.ott` | OpenDocument Text | 👁️ View only |
| `.rtf` | Rich Text Format | 👁️ View only, text only |
| `.mht`, `.mhtml` | Web archive | 👁️ View only, text only |
| `.doc` | Word 97–2003 | 🚧 Planned |

### 📚 eBooks

| Format | Description | Mode |
|---|---|---|
| `.fb2` | FictionBook | ✅ View and edit (paragraph text) |
| `.epub` | EPUB | 👁️ View only |
| `.mobi`, `.azw`, `.azw3`, `.pdb` | Kindle and Palm | 🚧 Planned |

## Planned and Future File Types

| Format | Status | Notes |
|---|---|---|
| `.doc` | 🚧 Planned | Needs a binary Word reader |
| `.ppt` | 🚧 Planned | Needs a binary PowerPoint reader |
| `.dbf` | 👁️ View only | dBase tables, read-only |
| `.xps`, `.oxps` | 👁️ View only | XML Paper Specification |
| `.mobi`, `.azw`, `.azw3`, `.pdb` | 🚧 Planned | Kindle and Palm eBooks |
| `.djvu`, `.ofd`, `.chm` | Under consideration | Need a rendering library |
| `.wps`, `.hwp`, `.pages`, `.wri`, `.key` | Not planned for now | Proprietary formats |
| `.wk1` to `.wk4`, `.1-2-3`, `.sylk`, `.dif` | Not planned for now | Legacy spreadsheet formats |
| `.mpp`, `.vsd`, `.vsdx`, `.dwg`, `.dxf` | Not planned for now | Project, diagram and CAD formats |

## Setup

1. Download the latest APK from [Releases](../../releases)
2. Install it and open EveryReader
3. On first launch, allow **All files access** so EveryReader can find your documents
4. Pick a category at the top of the screen and tap a file to open it

## Permissions and Privacy

- **All files access** is needed to scan your storage. Without it, Android hides most documents from apps.
- EveryReader **does not request internet access**. It has no network code, analytics, ads or crash reporting.
- Files never leave your phone. Opening, editing and saving all happen locally.

## Built With

- [Kotlin](https://kotlinlang.org/) and [Jetpack Compose](https://developer.android.com/jetpack/compose) with Material 3
- Android's built-in `PdfRenderer` for PDF display
- [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) for PDF editing
- [Apache POI](https://poi.apache.org/) for reading and writing `.xls` and `.xlsx`
- Standard Java XML parsing for Word, PowerPoint, OpenDocument and eBook files

## ❓ Frequently Asked Questions

### Why does EveryReader need All files access?
Android 11 and newer hide most documents from apps unless they have this permission. It's only used to find and open your files.

### Will editing change my original file?
It depends on the format. PDF lets you save a copy or overwrite the original. Text, spreadsheet, Word and eBook edits are written to the original file when you save, so keep backups of anything important.

### Why are some files view-only?
Reading a format is simpler than writing it. Editing needs a separate writer for each format, so those are added one at a time. The table above shows the current status.

### Why does a document or presentation look slightly different from Office?
Word and PowerPoint layouts are approximated from the file's contents. Line breaks, fonts and spacing can differ slightly. Text, images and basic formatting are kept.

### Can EveryReader open password-protected PDFs?
No. The file shows "Could not open this PDF."

### Does EveryReader work offline?
Yes. It needs no internet connection at any point.

### What is the minimum Android version?
Android 10 (API level 29) or newer.

## Bug Reports and Suggestions

- Report bugs or suggest features by opening an [issue](https://github.com/galaxyjammed/EveryReader/issues) or [discussion](https://github.com/galaxyjammed/EveryReader/discussions)
- When reporting a bug with a file, include the file type and what the screen shows.
