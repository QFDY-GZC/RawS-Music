package android.os;

/** Compile-only signatures; never package these classes into the application. */
public interface INetworkManagementService extends IInterface {
    void setFirewallChainEnabled(int chain, boolean enabled) throws RemoteException;
    void setUidFirewallRule(int chain, int uid, int rule) throws RemoteException;

    abstract class Stub extends Binder implements INetworkManagementService {
        public static INetworkManagementService asInterface(IBinder binder) {
            throw new UnsupportedOperationException("Compile-only system signature");
        }
    }
}
