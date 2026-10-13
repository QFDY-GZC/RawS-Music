#include "../../main/cpp/id3_mp4_compat.h"
#include <cassert>

int main(int argc, char** argv) {
    uint8_t id3[10] = {'I','D','3',4,0,0,0,7,20,118};
    assert(rawmedia::leadingId3Extent(id3, 10, 3747807) == 117376);
    assert(rawmedia::leadingId3Extent(id3, 9, 3747807) == 0);
    assert(rawmedia::leadingId3Extent(id3, 10, 100) == 0);
    id3[6] = 128;
    assert(rawmedia::leadingId3Extent(id3, 10, 3747807) == 0);
    uint8_t ftyp[12] = {0,0,0,32,'f','t','y','p','M','4','A',' '};
    assert(rawmedia::isMp4Ftyp(ftyp, 12, 32));
    assert(!rawmedia::isMp4Ftyp(ftyp, 12, 31));
    ftyp[4] = 'm';
    assert(!rawmedia::isMp4Ftyp(ftyp, 12, 32));
    if (argc == 3) {
        assert(rawmedia::mp4InputPrefix(argv[1]) == 117376);
        assert(rawmedia::mp4InputPrefix(argv[2]) == -1);
    }
}
