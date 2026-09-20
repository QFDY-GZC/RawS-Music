# HeyMelody 16.9.1 OPO protocol / integration coverage

## Signed device catalog

- 82 root profiles: 52 OPPO + 30 OnePlus.
- 63 use OPO RFCOMM 079A; 19 use legacy OPO RFCOMM 1107.
- 85 distinct `WhitelistConfigDTO.Function` fields are used by the bundled signed whitelist.
- Runtime owner selection requires device-reported `0x8103 productId` **and** exact whitelist RFCOMM UUID match.

## RawSMusic generic controls now wired

- ANC: `0x010C/0x810C` + `0x0404`, profile mode list plus runtime ANC bitmap.
- Fixed EQ preset: `0x010F/0x810F` + `0x0406`, profile/firmware-gated preset list.
- Custom EQ: `0x0122/0x8122` + `0x0418`, arbitrary dynamic band count; CREATE/UPDATE with device readback.
- Generic feature switches: `0x010D/0x810D` + `0x0403`; query list is generated per signed product profile, not globally.
- Spatial type: `0x012A/0x812A` + `0x0422`; options come from profile `spatialTypes`.
- Prompt volume: `0x0130/0x8130` + `0x0427`; HeyMelody widget default is 1..10, whitelist `promptVolumeRange` overrides it (e.g. 2..10).

Every write still requires: exact product profile + exact RFCOMM service + runtime capability bitmap + existing GET/readback + SET ACK + authoritative GET readback.

## Catalogued but intentionally not exposed as a one-shot setting

Hearing enhancement, ear scan, personalized ANC, fit detection, device-finding sound, health/spine functions, account binding, OTA, logs, tutorials and similar items have multi-step workflows or safety/identity implications. Their whitelist presence and command families are catalogued, but RawSMusic does not fabricate a single-toggle UI until the complete HeyMelody lifecycle is recovered.

## Free4 current signed capability facts

- Product ID `068C10`, RFCOMM 079A.
- Custom EQ: 6 bands `62, 250, 1000, 4000, 8000, 16000 Hz`, ±6 dB.
- Spatial types: `[0,1]`.
- Prompt volume: enabled; default HeyMelody range 1..10.
- Generic profile switches include wear detection, game mode, multi-device connect, high tone quality and control auto-volume.
- ANC/EQ/custom EQ have already been real-device validated in this project; newly generalized controls still require per-device real-hardware validation.
