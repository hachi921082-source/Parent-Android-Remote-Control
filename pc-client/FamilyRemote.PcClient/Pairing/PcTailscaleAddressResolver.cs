using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using FamilyRemote.PcClient.Common;

namespace FamilyRemote.PcClient.Pairing;

/// <summary>
/// このPCが自分自身のTailscale IPv4アドレスを検出する。
/// docs/plans/pc-client/pairing-pc-side/PLAN.md §8 参照 -- Android側TailscaleAddressResolver
/// （app/src/main/java/com/familyremote/app/pairing/TailscaleAddressResolver.kt）の検出ロジックを
/// 完全に踏襲する: NetworkInterface.GetAllNetworkInterfaces()を列挙し、TailscaleのCGNATレンジ
/// （100.64.0.0/10）に属する最初のIPv4ユニキャストアドレスを選ぶ。見つからなければnullを返す。
/// </summary>
public static class PcTailscaleAddressResolver
{
    public static string? Resolve()
    {
        NetworkInterface[] interfaces;
        try
        {
            interfaces = NetworkInterface.GetAllNetworkInterfaces();
        }
        catch (NetworkInformationException)
        {
            return null;
        }

        foreach (NetworkInterface nic in interfaces)
        {
            IPInterfaceProperties props;
            try
            {
                props = nic.GetIPProperties();
            }
            catch (NetworkInformationException)
            {
                continue;
            }

            foreach (UnicastIPAddressInformation unicast in props.UnicastAddresses)
            {
                if (unicast.Address.AddressFamily == AddressFamily.InterNetwork &&
                    IsTailscaleCgnatAddress(unicast.Address))
                {
                    return unicast.Address.ToString();
                }
            }
        }

        return null;
    }

    private static bool IsTailscaleCgnatAddress(IPAddress address)
    {
        byte[] bytes = address.GetAddressBytes();
        if (bytes.Length != 4)
        {
            return false;
        }

        return bytes[0] == Constants.TailscaleCgnatFirstOctet &&
            bytes[1] >= Constants.TailscaleCgnatSecondOctetMin &&
            bytes[1] <= Constants.TailscaleCgnatSecondOctetMax;
    }
}
