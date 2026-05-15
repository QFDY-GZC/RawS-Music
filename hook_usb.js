// hook_usb.js - 拦截 UAPP 中的 libusb 关键函数

console.log("[*] Hook script loaded");

const libusbModule = Process.getModuleByName("libusb1.0.so");
console.log("[+] libusb1.0.so loaded at " + libusbModule.base);

// 1. Hook libusb_control_transfer
const controlTransfer = Module.findExportByName("libusb1.0.so", "libusb_control_transfer");
if (controlTransfer) {
    Interceptor.attach(controlTransfer, {
        onEnter(args) {
            const bmReqType = args[1].toInt();
            const bReq = args[2].toInt();
            const wValue = args[3].toInt();
            const wIndex = args[4].toInt();
            const wLength = args[6].toInt();

            if ((bmReqType & 0x60) == 0x20) {
                let log = `[CTRL] bmReqType=0x${bmReqType.toString(16)} bReq=0x${bReq.toString(16)} wValue=0x${wValue.toString(16)} wIndex=0x${wIndex.toString(16)} wLen=${wLength}`;

                if (bReq === 0x01 && wValue === 0x0100) {
                    const data = args[5];
                    if (!data.isNull() && wLength >= 3) {
                        const freq = data.readU8() | (data.add(1).readU8() << 8) | (data.add(2).readU8() << 16);
                        log += ` [SET_SAMPLING_FREQ] -> ${freq} Hz`;
                    }
                }
                console.log(log);
            }
        }
    });
} else {
    console.log("[-] libusb_control_transfer not found!");
}

// 2. Hook libusb_submit_transfer
const submitTransfer = Module.findExportByName("libusb1.0.so", "libusb_submit_transfer");
if (submitTransfer) {
    Interceptor.attach(submitTransfer, {
        onEnter(args) {
            const transfer = args[0];
            const type = transfer.add(12).readU8();
            if (type == 1) {
                const numPkts = transfer.add(40).readS32();
                const isoDesc = transfer.add(48);
                let pktSizes = "";
                for (let i = 0; i < numPkts && i < 16; i++) {
                    const pktDesc = isoDesc.add(i * 12);
                    pktSizes += pktDesc.readU32() + " ";
                }
                console.log(`[ISO] numPackets=${numPkts} sizes=[${pktSizes}]`);
            }
        }
    });
} else {
    console.log("[-] libusb_submit_transfer not found!");
}

// 3. Hook libusb_set_interface_alt_setting
const setInterfaceAlt = Module.findExportByName("libusb1.0.so", "libusb_set_interface_alt_setting");
if (setInterfaceAlt) {
    Interceptor.attach(setInterfaceAlt, {
        onEnter(args) {
            console.log(`[ALT] interface=${args[1].toInt()} altSetting=${args[2].toInt()}`);
        }
    });
}

// 4. Hook libusb_claim_interface
const claimInterface = Module.findExportByName("libusb1.0.so", "libusb_claim_interface");
if (claimInterface) {
    Interceptor.attach(claimInterface, {
        onEnter(args) {
            console.log(`[CLAIM] interface=${args[1].toInt()}`);
        }
    });
}

// 5. Hook libusb_wrap_sys_device
const wrapSysDevice = Module.findExportByName("libusb1.0.so", "libusb_wrap_sys_device");
if (wrapSysDevice) {
    Interceptor.attach(wrapSysDevice, {
        onEnter(args) {
            console.log(`[WRAP] fd=${args[1].toInt()}`);
        }
    });
}

console.log("[*] Hooks attached");
