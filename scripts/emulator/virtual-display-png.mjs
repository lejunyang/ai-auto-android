// 功能用途：用 Node 标准库检查已由 CLI 校验的 PNG 像素，防止全黑帧被端到端验收当作成功。
import { inflateSync } from "node:zlib";

/** 仅处理 displayd 产生的 8 位 RGB/RGBA 非交错 PNG，拒绝超出固定尺寸预算的输入。 */
export function pngStats(data) {
  if (!data.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))) throw new Error("Invalid PNG signature");
  let width, height, channels;
  const chunks = [];
  for (let offset = 8; offset + 12 <= data.length;) {
    const length = data.readUInt32BE(offset), end = offset + 12 + length;
    if (end > data.length) throw new Error("Truncated PNG chunk");
    const type = data.toString("ascii", offset + 4, offset + 8);
    if (type === "IHDR") {
      width = data.readUInt32BE(offset + 8); height = data.readUInt32BE(offset + 12);
      channels = ({ 2: 3, 6: 4 })[data[offset + 17]];
      if (data[offset + 16] !== 8 || data[offset + 20] !== 0 || !channels || width < 1 || height < 1 || width > 2048 || height > 2048) throw new Error("Unsupported PNG layout");
    }
    if (type === "IDAT") chunks.push(data.subarray(offset + 8, end - 4));
    offset = end;
  }
  if (!channels) throw new Error("Missing PNG header");
  const stride = width * channels, expected = (stride + 1) * height;
  const raw = inflateSync(Buffer.concat(chunks), { maxOutputLength: expected });
  if (raw.length !== expected) throw new Error("Unexpected PNG scanline length");
  let previous = Buffer.alloc(stride), nonBlack = 0;
  const paeth = (a, b, c) => { const p = a + b - c, da = Math.abs(p - a), db = Math.abs(p - b), dc = Math.abs(p - c); return da <= db && da <= dc ? a : db <= dc ? b : c; };
  for (let y = 0; y < height; y++) {
    const row = Buffer.from(raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1)));
    const filter = raw[y * (stride + 1)];
    if (filter > 4) throw new Error("Invalid PNG filter");
    for (let x = 0; x < stride; x++) {
      const left = x >= channels ? row[x - channels] : 0, up = previous[x], corner = x >= channels ? previous[x - channels] : 0;
      row[x] = (row[x] + [0, left, up, Math.floor((left + up) / 2), paeth(left, up, corner)][filter]) & 255;
    }
    for (let x = 0; x < stride; x += channels) if (row[x] > 8 || row[x + 1] > 8 || row[x + 2] > 8) nonBlack++;
    previous = row;
  }
  return { width, height, nonBlack, pixels: width * height, nonBlackRatio: nonBlack / (width * height) };
}
