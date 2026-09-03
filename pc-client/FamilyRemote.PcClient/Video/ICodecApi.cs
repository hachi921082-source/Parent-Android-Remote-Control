using System.Runtime.InteropServices;

namespace FamilyRemote.PcClient.Video;

/// <summary>
/// Windows SDK <c>icodecapi.h</c>の<c>ICodecAPI</c>を手動で宣言したCOM相互運用インターフェース。
/// Vortice.MediaFoundation（本tierが導入したNuGetパッケージ、docs/plans/pc-client/
/// video-and-session/decode.PLAN.md §3参照）はこのインターフェースを型付きラッパーとして
/// 生成していない -- <c>IMFTransform</c>から<c>QueryInterface</c>で取得する必要があるため、
/// 実際のCOM ABI（IID <c>901db4c7-31ce-41a2-85dc-8fa0bf41b8da</c>、メソッド並び順は
/// <c>IsSupported</c>/<c>IsModifiable</c>/<c>GetParameterRange</c>/<c>GetParameterValues</c>/
/// <c>GetDefaultValue</c>/<c>GetValue</c>/<c>SetValue</c>/...）と一致する最小限の宣言をここに置く。
/// <see cref="MediaFoundationDecoder"/>は<c>SetValue</c>（CODECAPI_AVLowLatencyMode設定、
/// decode.PLAN.md §5）しか呼ばないため、それ以降のメソッド（RegisterForEvent等）は
/// 宣言していない -- COMのvtableディスパッチはインターフェース内の宣言順で先頭から決まるため、
/// 呼び出さないメソッドを後ろに追加宣言しない限りvtableスロットのずれは起きない。
/// <c>object</c>でVARIANT引数を表しているのは、.NETのCOM相互運用の既定マーシャリングが
/// <c>object</c>パラメータをVARIANTへ変換する標準的な挙動を利用したもの
/// （<c>bool</c>をボックス化するとVT_BOOLになる）。
/// </summary>
[ComImport]
[Guid("901db4c7-31ce-41a2-85dc-8fa0bf41b8da")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface ICodecApi
{
    void IsSupported([In] ref Guid api);
    void IsModifiable([In] ref Guid api);
    void GetParameterRange([In] ref Guid api, out object valueMin, out object valueMax, out object steppingDelta);
    void GetParameterValues([In] ref Guid api, out IntPtr values, out int valuesCount);
    void GetDefaultValue([In] ref Guid api, out object value);
    void GetValue([In] ref Guid api, out object value);
    void SetValue([In] ref Guid api, [In] ref object value);
}
