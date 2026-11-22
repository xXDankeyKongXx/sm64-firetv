#!/usr/bin/env python3
"""
Converts the prebuilt sound data (sound_data.ctl, sound_data.tbl, sequences.bin) from the 64-bit
layout sm64coopdx ships to the 32-bit layout a 32-bit ARM build needs.

The shipped files were assembled with tools/assemble_sound.py --bitwidth 64, so every pointer field
(ALSeqData.offset, bank/instrument/drum/sample pointers) is 8 bytes wide and some structs carry
4 bytes of padding. Re-assembling isn't possible without the ROM's samples, but every 32-bit
struct is smaller than its 64-bit form, so each struct is rewritten in place at its original
offset. All offsets (which are relative) stay valid, nothing else moves, and the sample offsets in
sound/samples_offsets.h stay correct. Book, loop and envelope data hold no pointers and are kept.

Usage: sound_to_32bit.py <build/us_pc/sound dir>   (rewrites the binaries and their .inc.c files)
"""
import os
import struct
import sys

LE = "<"


class Blob:
    def __init__(self, data):
        self.b = bytearray(data)

    def u8(self, o): return self.b[o]
    def u32(self, o): return struct.unpack_from(LE + "I", self.b, o)[0]
    def u64(self, o): return struct.unpack_from(LE + "Q", self.b, o)[0]
    def f32(self, o): return struct.unpack_from(LE + "f", self.b, o)[0]

    def clear(self, o, n): self.b[o:o + n] = bytes(n)
    def put(self, o, fmt, *v): struct.pack_into(LE + fmt, self.b, o, *v)


def fail(msg):
    sys.exit("sound_to_32bit: " + msg)


def convert_seqfile_header(blob):
    """ALSeqFile: s16 revision, s16 seqCount, then {ptr offset; u32 len} per entry.
    64-bit: 8-byte header (4 pad) + 16-byte entries. 32-bit: 4-byte header + 8-byte entries."""
    count = struct.unpack_from(LE + "H", blob.b, 2)[0]
    if blob.u32(4) != 0:
        fail("header doesn't look like the 64-bit layout (already converted?)")
    entries = []
    for i in range(count):
        o = 8 + 16 * i
        entries.append((blob.u64(o), blob.u32(o + 8)))
    blob.clear(4, 4 + 16 * count)
    for i, (off, ln) in enumerate(entries):
        blob.put(4 + 8 * i, "II", off, ln)
    return entries


def convert_bank(blob, start, length):
    """One sound bank: a 16-byte header (u32 counts), then the AudioBank data whose pointers
    are offsets from the start of that data."""
    num_inst, num_drums = blob.u32(start), blob.u32(start + 4)
    base = start + 16
    end = start + length

    def at(off, size):
        if off <= 0 or base + off + size > end:
            fail("bank at 0x%x: offset 0x%x out of range" % (start, off))
        return base + off

    # --- read everything (64-bit layout) ---
    drum_list = blob.u64(base)
    inst_ptrs = [blob.u64(base + 8 + 8 * i) for i in range(num_inst)]

    samples = {}   # offset -> (word0, addr, loop, book, len)
    def read_sound(o):
        s, tuning = blob.u64(o), blob.f32(o + 8)
        if s and s not in samples:
            p = at(s, 36)
            samples[s] = (blob.u32(p), blob.u64(p + 8), blob.u64(p + 16), blob.u64(p + 24), blob.u32(p + 32))
        return (s, tuning)

    instruments = {}
    for ip in inst_ptrs:
        if ip and ip not in instruments:
            p = at(ip, 64)
            instruments[ip] = (bytes(blob.b[p:p + 4]), blob.u64(p + 8),
                               [read_sound(p + 16 + 16 * k) for k in range(3)])

    drum_ptrs, drums = [], {}
    if drum_list and num_drums:
        p = at(drum_list, 8 * num_drums)
        drum_ptrs = [blob.u64(p + 8 * i) for i in range(num_drums)]
        for dp in drum_ptrs:
            if dp and dp not in drums:
                q = at(dp, 32)
                drums[dp] = (bytes(blob.b[q:q + 4]), read_sound(q + 8), blob.u64(q + 24))

    # --- write back (32-bit layout), each struct at its original offset ---
    blob.clear(base, 8 + 8 * num_inst)
    blob.put(base, "I", drum_list)
    for i, ip in enumerate(inst_ptrs):
        blob.put(base + 4 + 4 * i, "I", ip)

    for s, (w0, addr, loop, book, ln) in samples.items():
        p = base + s
        blob.clear(p, 36)
        blob.put(p, "IIIII", w0, addr, loop, book, ln)  # unused/loaded, sampleAddr, loop, book, size

    for ip, (head, env, sounds) in instruments.items():
        p = base + ip
        blob.clear(p, 64)
        blob.b[p:p + 4] = head                          # loaded, rangeLo, rangeHi, releaseRate
        blob.put(p + 4, "I", env)
        for k, (s, tuning) in enumerate(sounds):
            blob.put(p + 8 + 8 * k, "If", s, tuning)

    if drum_ptrs:
        p = base + drum_list
        blob.clear(p, 8 * num_drums)
        for i, dp in enumerate(drum_ptrs):
            blob.put(p + 4 * i, "I", dp)
        for dp, (head, (s, tuning), env) in drums.items():
            q = base + dp
            blob.clear(q, 32)
            blob.b[q:q + 4] = head                      # releaseRate, pan, loaded, pad
            blob.put(q + 4, "IfI", s, tuning, env)
    return len(instruments), len(drums), len(samples)


def verify_bank(blob, start, length, tbl_len):
    """Re-read the bank with the 32-bit layout and sanity-check every sample it reaches."""
    num_inst, num_drums = blob.u32(start), blob.u32(start + 4)
    base, size = start + 16, length - 16

    def ok_ptr(v, n): return 0 < v and v + n <= size

    def check_sound(o):
        s = blob.u32(o)
        if s == 0:
            return
        assert ok_ptr(s, 20), "sample ptr"
        p = base + s
        addr, loop, book, ln = blob.u32(p + 4), blob.u32(p + 8), blob.u32(p + 12), blob.u32(p + 16)
        assert ok_ptr(loop, 16) and ok_ptr(book, 8), "loop/book ptr"
        order, npred = struct.unpack_from(LE + "ii", blob.b, base + book)
        assert 0 < order <= 8 and 0 < npred <= 8, "book %d %d" % (order, npred)
        assert addr + ln <= tbl_len + 16, "sample data range"

    for i in range(num_inst):
        ip = blob.u32(base + 4 + 4 * i)
        if ip:
            assert ok_ptr(ip, 32), "instrument ptr"
            assert ok_ptr(blob.u32(base + ip + 4), 4), "envelope ptr"
            for k in range(3):
                check_sound(base + ip + 8 + 8 * k)
    dl = blob.u32(base)
    if dl and num_drums:
        for i in range(num_drums):
            dp = blob.u32(base + dl + 4 * i)
            if dp:
                assert ok_ptr(dp, 16), "drum ptr"
                check_sound(base + dp + 4)
                assert ok_ptr(blob.u32(base + dp + 12), 4), "drum envelope ptr"


def write_inc(path, data):
    with open(path + ".inc.c", "w") as f:
        f.write("".join("0x%X," % b for b in data))
        f.write("\n")


def main():
    if len(sys.argv) != 2:
        fail("usage: sound_to_32bit.py <build/us_pc/sound>")
    d = sys.argv[1]
    paths = {n: os.path.join(d, n) for n in ("sound_data.ctl", "sound_data.tbl", "sequences.bin")}
    blobs = {n: Blob(open(p, "rb").read()) for n, p in paths.items()}

    tbl_entries = convert_seqfile_header(blobs["sound_data.tbl"])
    convert_seqfile_header(blobs["sequences.bin"])
    ctl = blobs["sound_data.ctl"]
    banks = convert_seqfile_header(ctl)
    totals = [0, 0, 0]
    for off, ln in banks:
        for k, v in enumerate(convert_bank(ctl, off, ln)):
            totals[k] += v
    tbl_len = len(blobs["sound_data.tbl"].b)
    for i, (off, ln) in enumerate(banks):
        try:
            verify_bank(ctl, off, ln, tbl_len)
        except AssertionError as e:
            fail("bank %d failed verification: %s" % (i, e))

    for n, p in paths.items():
        with open(p, "wb") as f:
            f.write(blobs[n].b)
        write_inc(p, blobs[n].b)
    print("sound_to_32bit: %d banks (%d instruments, %d drums, %d samples), %d sample banks, %d sequences"
          % (len(banks), totals[0], totals[1], totals[2], len(tbl_entries),
             struct.unpack_from(LE + "H", blobs["sequences.bin"].b, 2)[0]))


if __name__ == "__main__":
    main()
