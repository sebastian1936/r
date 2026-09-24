use bytes::{Buf, BufMut, Bytes, BytesMut};
use std::io;
use tokio_util::codec::{Decoder, Encoder};
// 引入加密库
use chacha20::ChaCha20;
use chacha20::cipher::{KeyIvInit, StreamCipher};
use rand::Rng;
use crate::config::Config;

/// v2 混淆帧小帧填充桶：信令帧（注册/打洞/登录等 protobuf 小消息）尺寸固定，
/// 是明显的流量指纹；按桶填充 + 随机抖动把帧长抹平。大帧（视频流）只加小抖动。
const PAD_BUCKETS: [usize; 7] = [48, 96, 160, 256, 384, 640, 1024];

fn obfs_padding(plain_len: usize) -> usize {
    // inner = 4 字节长度 + payload
    let inner_len = plain_len + 4;
    for b in PAD_BUCKETS {
        if inner_len < b {
            let pad = b - inner_len;
            // 只在代价可接受时按桶补齐，避免小消息被撑到 KB 级
            if pad <= 200 {
                return pad + (rand::random::<u8>() % 16) as usize;
            }
        }
    }
    (rand::random::<u8>() % 16) as usize
}

/// 单个 UDP 数据报封装：混淆模式按 v2 帧加密，明文模式原样透传。
/// 用于不走 FramedSocket 的裸 UDP 信令（TestNat / PunchHoleSent），
/// 否则这些明文 protobuf 数据报既是指纹，也会被混淆 hbbs 当坏包丢弃。
pub fn wrap_datagram(msg: &[u8]) -> Vec<u8> {
    match Config::get_obfuscate_key() {
        None => msg.to_vec(),
        Some(key) => {
            let mut codec = BytesCodec::new_obfuscate(key);
            let mut out = BytesMut::with_capacity(msg.len() + 32);
            match codec.encode(Bytes::copy_from_slice(msg), &mut out) {
                Ok(()) => out.to_vec(),
                Err(_) => msg.to_vec(),
            }
        }
    }
}

/// 单个 UDP 数据报解封（wrap_datagram 的逆过程）
pub fn unwrap_datagram(buf: &[u8]) -> Option<Vec<u8>> {
    let mut codec = match Config::get_obfuscate_key() {
        None => return Some(buf.to_vec()),
        Some(key) => BytesCodec::new_obfuscate(key),
    };
    let mut src = BytesMut::from(buf);
    codec.decode(&mut src).ok().flatten().map(|f| f.to_vec())
}

/// P2P UDP 打洞探测包的内部标记（出现在解密后的明文里，线上抓到的始终是密文）。
/// 旧实现双方互发 0 字节数据报，20ms 起的固定空包突发是明显的行为指纹；
/// 混淆模式下改为带随机填充的探测包，明文模式仍返回空包保持官方兼容。
const PUNCH_PROBE_MAGIC: &[u8] = b"RDHP1";

/// 构造一个打洞探测数据报。
/// - 混淆模式：magic + 8~39 字节随机填充，整体套 v2 混淆帧（长度/内容均随机化）
/// - 明文模式：空 Vec，即官方 RustDesk 的 0 字节打洞包
pub fn wrap_punch_probe() -> Vec<u8> {
    if Config::get_obfuscate_key().is_none() {
        return Vec::new();
    }
    let pad = 8 + (rand::random::<u8>() % 32) as usize;
    let mut inner = Vec::with_capacity(PUNCH_PROBE_MAGIC.len() + pad);
    inner.extend_from_slice(PUNCH_PROBE_MAGIC);
    let mut noise = vec![0u8; pad];
    rand::thread_rng().fill(&mut noise[..]);
    inner.extend_from_slice(&noise);
    wrap_datagram(&inner)
}

/// KCP 数据报出站封装：明文模式透传，混淆模式套 v2 帧，
/// 消灭线上 KCP 明文头（cmd 0x81/0x82/0x83、24 字节固定结构）指纹
pub fn wrap_p2p_datagram(pkt: &[u8]) -> Vec<u8> {
    wrap_datagram(pkt)
}

/// 入站 P2P UDP 数据报分类结果
pub enum P2pDatagram {
    /// 对端打洞探测包，不喂 KCP
    Probe,
    /// KCP 数据包（已解混淆，明文模式为原包）
    Payload(Vec<u8>),
    /// 坏包/异模噪声/第三方注入，丢弃
    Invalid,
}

/// 分类入站 P2P UDP 数据报（wrap_punch_probe / wrap_p2p_datagram 的逆过程）。
/// 明文模式：空包=探测，非空=KCP，与官方行为一致；
/// 混淆模式：解帧后按 magic 区分探测包与 KCP 包，解帧失败一律丢弃。
pub fn classify_p2p_datagram(buf: &[u8]) -> P2pDatagram {
    if Config::get_obfuscate_key().is_none() {
        return if buf.is_empty() {
            P2pDatagram::Probe
        } else {
            P2pDatagram::Payload(buf.to_vec())
        };
    }
    match unwrap_datagram(buf) {
        Some(plain) if plain.starts_with(PUNCH_PROBE_MAGIC) => P2pDatagram::Probe,
        Some(plain) => P2pDatagram::Payload(plain),
        None => P2pDatagram::Invalid,
    }
}

#[derive(Debug, Clone, Copy)]
pub struct BytesCodec {
    state: DecodeState,
    raw: bool,
    max_packet_length: usize,
    obfuscate_key: Option<[u8; 32]>,
}

#[derive(Debug, Clone, Copy)]
enum DecodeState {
    Head,
    Data(usize),
}

impl Default for BytesCodec {
    fn default() -> Self {
        Self::new_auto()
    }
}

impl BytesCodec {
    /// 官方明文模式（帧格式与官方 RustDesk 完全一致，兼容 App Store 客户端/服务端）
    pub fn new() -> Self {
        Self {
            state: DecodeState::Head,
            raw: false,
            max_packet_length: usize::MAX,
            obfuscate_key: None,
        }
    }

    /// 混淆模式（ChaCha20 流密码伪装 payload 特征）
    pub fn new_obfuscate(key: [u8; 32]) -> Self {
        Self {
            state: DecodeState::Head,
            raw: false,
            max_packet_length: usize::MAX,
            obfuscate_key: Some(key),
        }
    }

    /// 按运行时 option `traffic-obfuscate` 自动选择混淆或官方明文模式
    pub fn new_auto() -> Self {
        match Config::get_obfuscate_key() {
            Some(key) => Self::new_obfuscate(key),
            None => Self::new(),
        }
    }

    /// 是否为官方明文模式（UDP 明文时需 set_raw 退化为透传，对齐官方行为）
    pub fn is_raw_compatible(&self) -> bool {
        self.obfuscate_key.is_none()
    }
    pub fn set_raw(&mut self) {
        self.raw = true;
    }

    pub fn set_max_packet_length(&mut self, n: usize) {
        self.max_packet_length = n;
    }

    fn decode_head(&mut self, src: &mut BytesMut) -> io::Result<Option<usize>> {
        if src.is_empty() {
            return Ok(None);
        }
        let head_len = ((src[0] & 0x3) + 1) as usize;
        if src.len() < head_len {
            return Ok(None);
        }
        let mut n = src[0] as usize;
        if head_len > 1 {
            n |= (src[1] as usize) << 8;
        }
        if head_len > 2 {
            n |= (src[2] as usize) << 16;
        }
        if head_len > 3 {
            n |= (src[3] as usize) << 24;
        }
        n >>= 2;
        if n > self.max_packet_length {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "Too big packet"));
        }
        src.advance(head_len);
        src.reserve(n);
        Ok(Some(n))
    }

    fn decode_data(&self, n: usize, src: &mut BytesMut) -> io::Result<Option<BytesMut>> {
        if src.len() < n {
            return Ok(None);
        }
        Ok(Some(src.split_to(n)))
    }
}

impl Decoder for BytesCodec {
    type Item = BytesMut;
    type Error = io::Error;

    fn decode(&mut self, src: &mut BytesMut) -> Result<Option<BytesMut>, io::Error> {
        if self.raw {
            if !src.is_empty() {
                let len = src.len();
                return Ok(Some(src.split_to(len)));
            } else {
                return Ok(None);
            }
        }
        let n = match self.state {
            DecodeState::Head => match self.decode_head(src)? {
                Some(n) => {
                    self.state = DecodeState::Data(n);
                    n
                }
                None => return Ok(None),
            },
            DecodeState::Data(n) => n,
        };

        match self.decode_data(n, src)? {
            Some(mut data) => {
                // v2 混淆帧：前 12 字节随机 nonce，其后为 ChaCha20(u32le 长度 || 明文 || 填充)。
                // 旧版固定全 0 nonce 会导致所有帧密钥流相同（相同前缀密文可异或还原），
                // 且帧长精确暴露 protobuf 消息尺寸。
                if let Some(key) = self.obfuscate_key {
                    if data.len() < 12 + 4 {
                        self.state = DecodeState::Head;
                        return Err(io::Error::new(
                            io::ErrorKind::InvalidData,
                            "too short obfs frame",
                        ));
                    }
                    let mut nonce = [0u8; 12];
                    nonce.copy_from_slice(&data[..12]);
                    let mut body = data.split_off(12);
                    let mut cipher = ChaCha20::new(&key.into(), &nonce.into());
                    cipher.apply_keystream(&mut body);
                    let real_len =
                        u32::from_le_bytes([body[0], body[1], body[2], body[3]]) as usize;
                    if body.len() < 4 + real_len {
                        self.state = DecodeState::Head;
                        return Err(io::Error::new(
                            io::ErrorKind::InvalidData,
                            "bad obfs frame length",
                        ));
                    }
                    data = BytesMut::from(&body[4..4 + real_len][..]);
                }

                self.state = DecodeState::Head;
                Ok(Some(data))
            }
            None => Ok(None),
        }
    }
}

// 用于类型约定
impl Encoder<Bytes> for BytesCodec {
    type Error = io::Error;

    fn encode(&mut self, data: Bytes, buf: &mut BytesMut) -> Result<(), io::Error> {
        if self.raw {
            buf.reserve(data.len());
            buf.put(data);
            return Ok(());
        }

        // 混淆模式 v2：随机 12 字节 nonce + ChaCha20(u32le 长度 || payload || 随机填充)，
        // nonce 随帧明文发送；明文模式（new()，官方兼容）保持原裸帧格式不变
        let payload = if let Some(key) = self.obfuscate_key {
            let mut nonce = [0u8; 12];
            rand::thread_rng().fill(&mut nonce[..]);
            let pad = obfs_padding(data.len());
            let mut inner = Vec::with_capacity(4 + data.len() + pad);
            inner.extend_from_slice(&(data.len() as u32).to_le_bytes());
            inner.extend_from_slice(&data);
            let mut padbuf = vec![0u8; pad];
            rand::thread_rng().fill(&mut padbuf[..]);
            inner.extend_from_slice(&padbuf);
            let mut cipher = ChaCha20::new(&key.into(), &nonce.into());
            cipher.apply_keystream(&mut inner);
            let mut out = Vec::with_capacity(12 + inner.len());
            out.extend_from_slice(&nonce);
            out.extend_from_slice(&inner);
            out
        } else {
            data.to_vec()
        };

        let len = payload.len();
        if len <= 0x3F {
            buf.put_u8((len << 2) as u8);
        } else if len <= 0x3FFF {
            buf.put_u16_le((len << 2) as u16 | 0x1);
        } else if len <= 0x3FFFFF {
            let h = (len << 2) as u32 | 0x2;
            buf.put_u16_le((h & 0xFFFF) as u16);
            buf.put_u8((h >> 16) as u8);
        } else if len <= 0x3FFFFFFF {
            buf.put_u32_le((len << 2) as u32 | 0x3);
        } else {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "Overflow"));
        }

        buf.extend_from_slice(&payload);
        Ok(())
    }
}