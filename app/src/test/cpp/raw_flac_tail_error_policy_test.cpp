#include "raw_flac_tail_error_policy.h"
#include <cassert>
int main() {
    assert(canSkipFlacTailDamage(true, true, 258666, 258000, 0));
    assert(!canSkipFlacTailDamage(true, true, 258666, 100000, 0));
    assert(!canSkipFlacTailDamage(true, false, 258666, 258000, 0));
    assert(!canSkipFlacTailDamage(false, true, 258666, 258000, 0));
    assert(!canSkipFlacTailDamage(true, true, 0, 258000, 0));
    assert(!canSkipFlacTailDamage(true, true, 258666, 258000, 16));
    assert(!canSkipFlacTailDamage(true, true, 1000, 0, 0));
}
