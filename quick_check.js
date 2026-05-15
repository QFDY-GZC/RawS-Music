// quick_check.js - SETINTERFACE only
setTimeout(function() {
    var libc = Process.findModuleByName("libc.so");
    if (!libc) {
        console.log("[!] libc.so not found!");
        return;
    }
    
    var ioctl = libc.findExportByName("ioctl");
    if (!ioctl) {
        console.log("[!] ioctl not found!");
        return;
    }
    
    Interceptor.attach(ioctl, {
      onEnter: function(args) {
        var req = args[1].toInt32() >>> 0;
        if (req == 0x80085504) { // SETINTERFACE
          var intf = args[2].readU32();
          var alt = args[2].add(4).readU32();
          console.log("[SETINTERFACE] interface=" + intf + " alt=" + alt);
        }
      }
    });
    
    console.log("[*] Hooks ready. Play 44.1kHz music in UAPP!");
}, 1000);
