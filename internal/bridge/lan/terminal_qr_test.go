package lan

// 测试用途：独立还原终端二维码的数据区，验证生产 representation 精确承载邀请且有界。

import (
	"bytes"
	"context"
	"errors"
	"strings"
	"testing"
)

func TestTerminalQRProviderEncodesExactPayloadDeterministically(t *testing.T) {
	payload := bytes.Repeat([]byte(`{"kind":"ai-auto-lan-invitation","value":"安全邀请"}`), 24)
	provider := NewTerminalQRProvider()

	first, err := provider.Encode(context.Background(), payload)
	if err != nil {
		t.Fatalf("Encode() error = %v", err)
	}
	second, err := provider.Encode(context.Background(), payload)
	if err != nil {
		t.Fatalf("second Encode() error = %v", err)
	}

	if first.Format != QRFormatTerminalUTF8 || !first.Generated {
		t.Fatalf("representation metadata = %#v", first)
	}
	if !bytes.Equal(first.Data, second.Data) {
		t.Fatal("terminal QR is not deterministic")
	}
	if len(first.Data) == 0 || len(first.Data) > MaxTerminalQRBytes {
		t.Fatalf("terminal QR bytes = %d", len(first.Data))
	}
	matrix := parseTerminalQR(t, string(first.Data))
	if len(matrix) != terminalQRVersionSize+terminalQRQuietZone*2 {
		t.Fatalf("matrix size = %d", len(matrix))
	}
	assertFinderPattern(t, matrix, terminalQRQuietZone, terminalQRQuietZone)
	assertFinderPattern(
		t,
		matrix,
		terminalQRQuietZone+terminalQRVersionSize-7,
		terminalQRQuietZone,
	)
	assertFinderPattern(
		t,
		matrix,
		terminalQRQuietZone,
		terminalQRQuietZone+terminalQRVersionSize-7,
	)
	if decoded := decodeTerminalQRData(t, matrix); !bytes.Equal(decoded, payload) {
		t.Fatalf("decoded payload mismatch: got %d bytes, want %d", len(decoded), len(payload))
	}
}

func TestTerminalQRProviderRejectsOversizedPayloadAndCancellation(t *testing.T) {
	provider := NewTerminalQRProvider()
	if _, err := provider.Encode(
		context.Background(),
		bytes.Repeat([]byte{0x42}, MaxTerminalQRPayloadBytes+1),
	); ErrorCode(err) != CodeQRCapacityExceeded {
		t.Fatalf("oversized payload error = %v", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := provider.Encode(ctx, []byte("cancelled")); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancelled error = %v", err)
	}
}

func parseTerminalQR(t *testing.T, rendered string) [][]bool {
	t.Helper()
	lines := strings.Split(strings.TrimSuffix(rendered, "\n"), "\n")
	if len(lines) == 0 {
		t.Fatal("terminal QR has no lines")
	}
	width := len([]rune(lines[0]))
	matrix := make([][]bool, len(lines)*2)
	for index := range matrix {
		matrix[index] = make([]bool, width)
	}
	for row, line := range lines {
		runes := []rune(line)
		if len(runes) != width {
			t.Fatalf("line %d width = %d, want %d", row, len(runes), width)
		}
		for column, value := range runes {
			switch value {
			case ' ':
			case '▀':
				matrix[row*2][column] = true
			case '▄':
				matrix[row*2+1][column] = true
			case '█':
				matrix[row*2][column] = true
				matrix[row*2+1][column] = true
			default:
				t.Fatalf("unexpected terminal rune %q", value)
			}
		}
	}
	if len(matrix) > width {
		matrix = matrix[:width]
	}
	return matrix
}

func assertFinderPattern(t *testing.T, matrix [][]bool, left, top int) {
	t.Helper()
	for offsetY := 0; offsetY < 7; offsetY++ {
		for offsetX := 0; offsetX < 7; offsetX++ {
			want := offsetX == 0 || offsetX == 6 || offsetY == 0 || offsetY == 6 ||
				(offsetX >= 2 && offsetX <= 4 && offsetY >= 2 && offsetY <= 4)
			if matrix[top+offsetY][left+offsetX] != want {
				t.Fatalf("finder (%d,%d) module (%d,%d) mismatch", left, top, offsetX, offsetY)
			}
		}
	}
}

func decodeTerminalQRData(t *testing.T, withQuietZone [][]bool) []byte {
	t.Helper()
	size := len(withQuietZone) - terminalQRQuietZone*2
	matrix := make([][]bool, size)
	for row := range matrix {
		matrix[row] = append(
			[]bool(nil),
			withQuietZone[row+terminalQRQuietZone][terminalQRQuietZone:terminalQRQuietZone+size]...,
		)
	}
	function := terminalQRFunctionMap()
	mask := decodeTerminalQRMask(t, matrix)
	bits := make([]bool, 0, terminalQRTotalCodewords*8)
	upward := true
	for right := size - 1; right >= 1; right -= 2 {
		if right == 6 {
			right--
		}
		for vertical := 0; vertical < size; vertical++ {
			row := vertical
			if upward {
				row = size - 1 - vertical
			}
			for offset := 0; offset < 2; offset++ {
				column := right - offset
				if function[row][column] {
					continue
				}
				value := matrix[row][column]
				if terminalQRMaskApplies(mask, column, row) {
					value = !value
				}
				bits = append(bits, value)
			}
		}
		upward = !upward
	}
	if len(bits) < terminalQRTotalCodewords*8 {
		t.Fatalf("data bits = %d", len(bits))
	}
	codewords := bitsToBytes(bits[:terminalQRTotalCodewords*8])
	data := deinterleaveTerminalQRData(t, codewords)
	reader := qrTestBitReader{bytes: data}
	if mode := reader.read(t, 4); mode != 0b0100 {
		t.Fatalf("mode = %04b", mode)
	}
	length := reader.read(t, 16)
	if length > len(data) {
		t.Fatalf("payload length = %d", length)
	}
	payload := make([]byte, length)
	for index := range payload {
		payload[index] = byte(reader.read(t, 8))
	}
	return payload
}

func decodeTerminalQRMask(t *testing.T, matrix [][]bool) int {
	t.Helper()
	size := len(matrix)
	var value int
	for index := 0; index < 15; index++ {
		var bit bool
		switch {
		case index <= 5:
			bit = matrix[index][8]
		case index == 6:
			bit = matrix[7][8]
		case index == 7:
			bit = matrix[8][8]
		case index == 8:
			bit = matrix[8][7]
		default:
			bit = matrix[8][14-index]
		}
		if bit {
			value |= 1 << index
		}
	}
	value ^= 0x5412
	data := value >> 10
	mask := data & 0x7
	if data>>3 != 0b01 {
		t.Fatalf("format EC level = %02b", data>>3)
	}
	if matrix[size-8][8] != true {
		t.Fatal("fixed dark module is not set")
	}
	return mask
}

func deinterleaveTerminalQRData(t *testing.T, codewords []byte) []byte {
	t.Helper()
	const shortBlocks = 3
	const longBlocks = 10
	const shortData = 117
	const longData = 118
	const ecc = 30
	blocks := make([][]byte, shortBlocks+longBlocks)
	for index := range blocks {
		length := shortData
		if index >= shortBlocks {
			length = longData
		}
		blocks[index] = make([]byte, length)
	}
	position := 0
	for dataIndex := 0; dataIndex < longData; dataIndex++ {
		for blockIndex := range blocks {
			if dataIndex < len(blocks[blockIndex]) {
				if position >= len(codewords) {
					t.Fatal("interleaved data truncated")
				}
				blocks[blockIndex][dataIndex] = codewords[position]
				position++
			}
		}
	}
	position += len(blocks) * ecc
	if position != len(codewords) {
		t.Fatalf("interleaved codeword count = %d, consumed %d", len(codewords), position)
	}
	data := make([]byte, 0, terminalQRDataCodewords)
	for _, block := range blocks {
		data = append(data, block...)
	}
	return data
}

func bitsToBytes(bits []bool) []byte {
	result := make([]byte, len(bits)/8)
	for index, bit := range bits {
		if bit {
			result[index/8] |= 1 << (7 - index%8)
		}
	}
	return result
}

type qrTestBitReader struct {
	bytes  []byte
	offset int
}

func (reader *qrTestBitReader) read(t *testing.T, count int) int {
	t.Helper()
	result := 0
	for range count {
		if reader.offset >= len(reader.bytes)*8 {
			t.Fatal("QR bit stream truncated")
		}
		result <<= 1
		result |= int(reader.bytes[reader.offset/8] >> (7 - reader.offset%8) & 1)
		reader.offset++
	}
	return result
}
