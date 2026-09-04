using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using FamilyRemote.PcClient.Common;

namespace FamilyRemote.PcClient.Pairing;

/// <summary>
/// このPCが自分自身のTailscale IPv4アドレスを検出する。
/// docs/plans/pc-client/pairing-pc-side/PLAN.md §8 参照 -- Android側TailscaleAddressResolver
/// （app/src/main/java/com/familyremote/app/pairing/TailscaleAddressResolver.kt）の検出ロジックを
/// 完全に踏襲する: まず名前が厳密に"tailscale0"であるインターフェースを優先して探し、そこに
/// CGNATレンジ（100.64.0.0/10）のIPv4アドレスがあればそれを返す。見つからない場合のみ、
/// NetworkInterface.GetAllNetworkInterfaces()を列挙順に走査するフォールバックへ進む。
/// </summary>
public static class PcTailscaleAddressResolver
{
    // Android側 TailscaleAddressResolver.kt の private const val TAILSCALE_INTERFACE_NAME と
    // 同じスコープ（この実装内のみで使う実装詳細）に合わせ、共有Constants.csではなくローカルに置く。
    private const string TailscaleInterfaceName = "tailscale0";

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

        NetworkInterface? byName = Array.Find(interfaces, nic => nic.Name == TailscaleInterfaceName);
        if (byName is not null)
        {
            string? preferred = FindCgnatAddress(byName);
            if (preferred is not null)
            {
                return preferred;
            }
        }

        foreach (NetworkInterface nic in interfaces)
        {
            string? address = FindCgnatAddress(nic);
            if (address is not null)
            {
                return address;
            }
        }

        return null;
    }

    private static string? FindCgnatAddress(NetworkInterface nic)
    {
        IPInterfaceProperties props;
        try
        {
            props = nic.GetIPProperties();
        }
        catch (NetworkInformationException)
        {
            return null;
        }

        foreach (UnicastIPAddressInformation unicast in props.UnicastAddresses)
        {
            if (unicast.Address.AddressFamily == AddressFamily.InterNetwork &&
                IsTailscaleCgnatAddress(unicast.Address))
            {
                return unicast.Address.ToString();
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
