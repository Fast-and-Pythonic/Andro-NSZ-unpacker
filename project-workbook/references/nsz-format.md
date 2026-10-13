# Reference: NSZ/NCZ format (analysis of the Python reference nicoboss/nsz)

> An external spec — about the format, not our code. Kept as-is, from an analysis of
> the reference implementation. For our engine's details see
> [../subsystems/native-engine.md](../subsystems/native-engine.md).

===============================================================================

Explore nsz reference project
Perfect! Now I have a comprehensive understanding of the NSZ reference implementation. Let me compile a detailed report:
NSZ Reference Implementation - Complete Analysis
I have thoroughly explored the NSZ reference project at /c/Users/Boss/AndroidStudioProjects/nsz. Here's a comprehensive guide to the standard implementation for NSZ decompression:



= 1. PROJECT STRUCTURE

The project is organized as follows:
nsz/
├── nsz/                           # Main package
│   ├── __init__.py               # Main entry point, compression/decompression dispatch
│   ├── NszDecompressor.py        # Core decompression logic (300 lines)
│   ├── BlockDecompressorReader.py # Block-based decompression (68 lines)
│   ├── BlockCompressor.py        # Block compression logic (292 lines)
│   ├── Header.py                 # Header structures (26 lines)
│   ├── SectionFs.py              # Section filesystem utilities
│   ├── PathTools.py              # Path utilities
│   ├── Fs/                       # Filesystem module
│   │   ├── BaseFs.py             # Base filesystem class
│   │   ├── File.py               # Base file handling
│   │   ├── Nca.py                # NCA archive format
│   │   ├── Nsp.py                # NSP container
│   │   ├── Pfs0.py               # PFS0 filesystem
│   │   ├── Hfs0.py               # HFS0 filesystem
│   │   ├── Type.py               # Enumerations for crypto/content types
│   │   └── [other file types]
│   ├── nut/                      # Cryptographic utilities
│   │   ├── aes128.py
│   │   ├── Keys.py
│   │   └── [utilities]
│   └── gui/                      # GUI components
├── nsz.py                        # Command-line entry point
└── README.md                     # Documentation



= 2. CORE DECOMPRESSION LOGIC (NszDecompressor.py)
Main Functions:
• decompress(filePath, outputDir, fixPadding, statusReportInfo, pleaseNoPrint) - Entry point for all decompression
• verify(filePath, fixPadding, raiseVerificationException, ...) - Verification functionality
• __decompressNcz(nspf, f, statusReportInfo, pleaseNoPrint) - Single NCA decompression
• __decompressContainer(readContainer, writeContainer, ...) - Container decompression (NSP/XCI)
• __getDecompressedNczSize(nspf) - Calculate decompressed size
• __decompressNsz() - NSZ→NSP decompression
• __decompressXcz() - XCZ→XCI decompression
Key Constants:
• UNCOMPRESSABLE_HEADER_SIZE = 0x4000 - NCA header not compressed
• CHUNK_SZ = 0x100000 - Reading/writing chunk size (1 MB)



= 3. NCZ FILE FORMAT STRUCTURE
NCZ Header Format (at offset 0x4000):
class Section:
    def __init__(self, f):
        self.offset = f.readInt64()           # Offset in decompressed data
        self.size = f.readInt64()             # Section size
        self.cryptoType = f.readInt64()       # Crypto type (1=none, 3=CTR, 4=BKTR)
        f.readInt64()                         # Padding
        self.cryptoKey = f.read(16)           # AES key for re-encryption
        self.cryptoCounter = f.read(16)       # CTR counter for re-encryption

class Block:
    def __init__(self, f):
        self.magic = f.read(8)                # b'NCZBLOCK'
        self.version = f.readInt8()           # Block format version
        self.type = f.readInt8()              # Block type
        self.unused = f.readInt8()            # Unused
        self.blockSizeExponent = f.readInt8() # 2^x = block size (14-32)
        self.numberOfBlocks = f.readInt32()   # Number of blocks
        self.decompressedSize = f.readInt64() # Total decompressed size
        self.compressedBlockSizeList = [readInt32() for _ in range(self.numberOfBlocks)]

NCZ File Layout:
[0x0000 - 0x4000]         : Encrypted NCA header (copy of original)
[0x4000]                  : "NCZSECTN" magic + section count (8 bytes)
[0x4008 - 0x4008+...]     : Section headers (64 bytes each)
[0x4008 + sections]       : Optional "NCZBLOCK" header (block compression)
[offset + header]         : Zstandard compressed stream → EOF



= 4. DECOMPRESSION WORKFLOW

For Single NCA (__decompressNcz):
1. Read header (0x4000 bytes) - Write directly to output (unencrypted)
2. Parse NCZSECTN magic - Verify b'NCZSECTN'
3. Read section count - Read Int64
4. Read section headers - For each section, read: offset, size, cryptoType, cryptoKey, cryptoCounter
5. Check for block compression - Look for b'NCZBLOCK' magic
6. Setup decompressor:
◦ If block compression: Create BlockDecompressorReader
◦ Otherwise: Create ZstdDecompressor().stream_reader()
7. Process sections:
for each section:
    for chunk in section (0x10000 bytes at a time):
        read compressed chunk
        if section uses crypto (type 3 or 4):
            setup AESCTR with section key/counter
            encrypt decompressed chunk
        write to output file
        update SHA256 hash
8. Return: (bytes_written, sha256_hexdigest)

For Containers (NSZ/XCZ):
1. Open container (NSP/XCI)
2. Create output container stream (Pfs0Stream or Hfs0Stream)
3. For each file in container:
◦ If not .ncz: Copy directly (verify hash if non-cnmt NCA)
◦ If .ncz: Decompress using __decompressNcz()
4. Update container headers
5. Verify all hashes match expected values



= 5. BLOCK DECOMPRESSION (BlockDecompressorReader.py)
Key Class: BlockDecompressorReader
class BlockDecompressorReader:
    Position = 0                    # Current position in decompressed stream
    BlockHeader = None              # Block header info
    CurrentBlock = b""              # Current decompressed block data
    CurrentBlockId = -1             # Current block ID

    def __init__(self, nspf, BlockHeader):
        self.BlockHeader = BlockHeader
        self.BlockSize = 2**BlockHeader.blockSizeExponent
        # Build offset list for each compressed block
        self.CompressedBlockOffsetList = [...]
        self.CompressedBlockSizeList = BlockHeader.compressedBlockSizeList

    def __decompressBlock(self, blockID):
        # Calculate decompressed block size (full or remainder)
        decompressedBlockSize = self.BlockSize
        if blockID is last and has remainder:
            decompressedBlockSize = remainder
        
        # Read compressed block from file
        self.nspf.seek(self.CompressedBlockOffsetList[blockID])
        compressedData = self.nspf.read(self.CompressedBlockSizeList[blockID])
        
        # Decompress or use as-is
        if compressedSize < decompressedBlockSize:
            return ZstdDecompressor().decompress(compressedData)
        else:
            return compressedData  # Not compressed, use as-is

    def read(self, length):
        # Read across block boundaries
        buffer = b""
        blockOffset = self.Position % self.BlockSize
        blockID = self.Position // self.BlockSize
        
        while len(buffer) - blockOffset < length:
            buffer += self.__decompressBlock(blockID)
            blockID += 1
        
        result = buffer[blockOffset:blockOffset+length]
        self.Position += length
        return result
Key Characteristics:
• Allows random read access within compressed blocks
• Each block decompresses independently
• Block size is configurable (2^14 to 2^32)
• Blocks smaller than decompressed size are stored uncompressed



= 6. FILE HIERARCHY & ENCRYPTION

NCA Structure:
NcaHeader:
    - magic (NCA3)
    - contentType (PROGRAM, META, CONTROL, DATA, etc.)
    - cryptoType, keyIndex
    - 4 sections with encryption info
    - titleId, contentIndex
    - keyBlock (encrypted section keys)

Nca (file wrapper):
    - header: NcaHeader
    - sectionFilesystems: [BaseFs, ...]  # Each section's FS
    - sections: [section metadata]
Encryption Handling:
• cryptoType = 1: No encryption
• cryptoType = 3: CTR (Counter) mode
• cryptoType = 4: BKTR (patched)
• Each section has its own key and counter



= 7. CONTAINER STRUCTURES

NSP (PFS0) - Partition File System 0:
• Magic: b'PFS0'
• Header: File count, string table size
• File entries: offset, size, name offset (0x18 bytes each)
• String table: Null-terminated filenames
• File data
XCI Structure:
• Header with HFS0 partition table
• Multiple partitions (normal, logo, update, secure) in HFS0 format
• Each partition is HFS0 (Hash File System 0)



= 8. KEY CLASSES AND RESPONSIBILITIES

Class
File
Responsibility
NszDecompressor
NszDecompressor.py
Main decompression orchestration
BlockDecompressorReader
BlockDecompressorReader.py
Streaming block decompression
Header.Section
Header.py
NCZ section metadata
Header.Block
Header.py
NCZ block header parsing
Nca
Fs/Nca.py
NCA file parsing and section access
NcaHeader
Fs/Nca.py
NCA header parsing
Pfs0
Fs/Pfs0.py
PFS0 container reading/writing
Pfs0Stream
Fs/Pfs0.py
Writing new PFS0 containers
BaseFs
Fs/BaseFs.py
Base filesystem with encryption support
File
Fs/File.py
Buffered file I/O with crypto support
AESCTR
nut/aes128.py
AES-CTR encryption/decryption



= 9. DECOMPRESSION ALGORITHM DETAILS

NCZ Decompression Process:
1. Read uncompressable header (0x4000 bytes) - Always from beginning
2. Parse NCZ metadata:
◦ Magic: "NCZSECTN" (8 bytes)
◦ Section count: Int64
◦ For each section: offset, size, cryptoType, key, counter (64 bytes)
3. Check for block compression:
◦ Peek at next 8 bytes for "NCZBLOCK" magic
◦ If present: Parse block header and use BlockDecompressorReader
◦ Otherwise: Use ZstdDecompressor stream reader
4. Decompress sections:
◦ Process in order by offset
◦ For first section: Skip uncompressed gap if exists
◦ Read 0x10000-byte chunks
◦ Decompress with zstd (or BlockDecompressorReader)
◦ Re-encrypt if cryptoType is 3 or 4
◦ Write to output
5. Verify: SHA256 hash during decompression



= 10. HASH VERIFICATION
Verification Strategy:
• For NCA files: SHA256 of entire decompressed file
◦ Compared against filename prefix (first 32 chars of hash)
◦ Can be verified against titlekey database
• For containers:
◦ Each .nca file verified independently (except cnmt.nca)
◦ Container-level SHA256 verification optional
◦ Supports "ticketless" dumps (no hash verification)



= 11. IMPORTANT CONSTANTS AND PATTERNS

Size Constants:
• NCA header: 0x4000 bytes (uncompressed)
• Standard chunk: 0x10000 bytes (64 KB)
• Block size exponent: 14-32 (produces 16 KB - 4 GB blocks)
• Alignment: 0x20 bytes (for string tables)
File Detection:
• .ncz = compressed NCA

• .nsz = NSP with compressed NCAs
• .xcz = XCI with compressed NCAs

• .nsp = uncompressed NSP
• .xci = uncompressed XCI

Cryptographic Operations:
• NCA header encrypted with AES-XTS (header key from Keys.txt)
• Section keys unwrapped using area keys
• Section data re-encrypted with AES-CTR after decompression



= 12. REFERENCE FILES LOCATION
All referenced files in this analysis are located at:
• /c/Users/Boss/AndroidStudioProjects/nsz/nsz/ - Core implementation
• /c/Users/Boss/AndroidStudioProjects/nsz/nsz/Fs/ - Filesystem classes
• /c/Users/Boss/AndroidStudioProjects/nsz/nsz/nut/ - Cryptographic utilities
This reference implementation provides the authoritative standard that your Android implementation should match for NSZ decompression compatibility. agentId: a7c9231 (for resuming to continue this agent's work if needed) <usage>total_tokens: 59364 tool_uses: 28 duration_ms: 109962</usage>

===============================================================================







