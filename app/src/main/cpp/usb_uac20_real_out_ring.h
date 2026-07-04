#pragma once

#include <cstdint>
#include <string>

namespace rawsmusic::usb {

// Dry-run skeleton for the future real OUT transfer ring. This stage does NOT
// submit any libusb transfer and does NOT play real PCM. It only records the
// ring configuration that a future executor would use.
struct Uac20RealOutRingConfig {
    int endpoint = 0;
    int transferCount = 4;
    int packetsPerTransfer = 8;
    int transferBytes = 0;
    int queueBytes = 0;
    int preallocatedBytes = 0;
    int frameBytes = 0;
};

struct Uac20RealOutRingStats {
    bool initialized = false;
    bool dryRunOnly = true;
    bool readyForFeeder = false;
    int endpoint = 0;
    int transferCount = 0;
    int packetsPerTransfer = 0;
    int transferBytes = 0;
    int queueBytes = 0;
    int preallocatedBytes = 0;
    int frameBytes = 0;
    std::string summary;
};

class Uac20RealOutRing {
public:
    bool configure(const Uac20RealOutRingConfig& config);
    Uac20RealOutRingStats snapshot() const;

private:
    Uac20RealOutRingConfig config_{};
    Uac20RealOutRingStats stats_{};
};

std::string describeUac20RealOutRingStats(const Uac20RealOutRingStats& stats);

} // namespace rawsmusic::usb
