package lan

// 功能用途：以固定有界的纯 Go QR Model 2 编码 invitation，并渲染为无需文件或 GUI 的
// UTF-8 终端表示。

import (
	"bytes"
	"context"
	"math"
)

const (
	// QRFormatTerminalUTF8 是 stdout 可直接显示的黑白半块字符二维码格式。
	QRFormatTerminalUTF8 = "terminal-utf8-v1"
	// MaxTerminalQRPayloadBytes 限制固定 Version 28-L byte segment 的最大内容。
	MaxTerminalQRPayloadBytes = 1528
	// MaxTerminalQRBytes 限制 UTF-8 terminal representation 的最大编码结果。
	MaxTerminalQRBytes = 29 * 1024

	terminalQRVersion        = 28
	terminalQRVersionSize    = 17 + terminalQRVersion*4
	terminalQRQuietZone      = 4
	terminalQRDataCodewords  = 1531
	terminalQRTotalCodewords = 1921
	terminalQRECCCodewords   = 30
	terminalQRRemainderBits  = 3
)

var terminalQRAlignmentCenters = [...]int{6, 26, 50, 74, 98, 122}

// TerminalQRProvider 生成固定 Version 28、L 级纠错的 QR Model 2 terminal representation。
type TerminalQRProvider struct{}

// NewTerminalQRProvider 创建不依赖 CGO、外部命令或持久化文件的生产 provider。
func NewTerminalQRProvider() TerminalQRProvider {
	return TerminalQRProvider{}
}

// Encode 将原始 invitation bytes 原样编码为有界 QR byte segment。
func (TerminalQRProvider) Encode(
	ctx context.Context,
	payload []byte,
) (QRRepresentation, error) {
	if err := ctx.Err(); err != nil {
		return QRRepresentation{}, err
	}
	if len(payload) == 0 || len(payload) > MaxTerminalQRPayloadBytes {
		return QRRepresentation{}, fail(
			CodeQRCapacityExceeded,
			"invitation payload exceeds the terminal QR capacity",
		)
	}
	dataCodewords := terminalQRData(payload)
	defer clear(dataCodewords)
	interleaved := terminalQRInterleave(dataCodewords)
	defer clear(interleaved)
	bits := terminalQRCodewordBits(interleaved)
	defer clear(bits)
	matrix := terminalQRBestMatrix(bits)
	rendered := terminalQRRender(matrix)
	if len(rendered) > MaxTerminalQRBytes {
		clear(rendered)
		return QRRepresentation{}, fail(
			CodeQRCapacityExceeded,
			"terminal QR representation exceeds the output limit",
		)
	}
	if err := ctx.Err(); err != nil {
		clear(rendered)
		return QRRepresentation{}, err
	}
	return QRRepresentation{
		Format:    QRFormatTerminalUTF8,
		Data:      rendered,
		Generated: true,
	}, nil
}

func terminalQRData(payload []byte) []byte {
	writer := terminalQRBitWriter{
		bytes: make([]byte, 0, terminalQRDataCodewords),
	}
	writer.append(0b0100, 4)
	writer.append(uint32(len(payload)), 16)
	for _, value := range payload {
		writer.append(uint32(value), 8)
	}
	remainingBits := terminalQRDataCodewords*8 - writer.bitLength
	writer.append(0, min(4, remainingBits))
	if remainder := writer.bitLength % 8; remainder != 0 {
		writer.append(0, 8-remainder)
	}
	for paddingIndex := 0; len(writer.bytes) < terminalQRDataCodewords; paddingIndex++ {
		if paddingIndex%2 == 0 {
			writer.bytes = append(writer.bytes, 0xec)
		} else {
			writer.bytes = append(writer.bytes, 0x11)
		}
	}
	return writer.bytes
}

func terminalQRInterleave(data []byte) []byte {
	const shortBlockCount = 3
	const longBlockCount = 10
	const shortDataCount = 117
	const longDataCount = 118
	blocks := make([]terminalQRBlock, 0, shortBlockCount+longBlockCount)
	offset := 0
	for blockIndex := 0; blockIndex < shortBlockCount+longBlockCount; blockIndex++ {
		dataCount := shortDataCount
		if blockIndex >= shortBlockCount {
			dataCount = longDataCount
		}
		blockData := append([]byte(nil), data[offset:offset+dataCount]...)
		offset += dataCount
		blocks = append(blocks, terminalQRBlock{
			data: blockData,
			ecc:  terminalQRReedSolomon(blockData, terminalQRECCCodewords),
		})
	}
	result := make([]byte, 0, terminalQRTotalCodewords)
	for dataIndex := 0; dataIndex < longDataCount; dataIndex++ {
		for blockIndex := range blocks {
			if dataIndex < len(blocks[blockIndex].data) {
				result = append(result, blocks[blockIndex].data[dataIndex])
			}
		}
	}
	for eccIndex := 0; eccIndex < terminalQRECCCodewords; eccIndex++ {
		for blockIndex := range blocks {
			result = append(result, blocks[blockIndex].ecc[eccIndex])
		}
	}
	for blockIndex := range blocks {
		clear(blocks[blockIndex].data)
		clear(blocks[blockIndex].ecc)
	}
	return result
}

func terminalQRCodewordBits(codewords []byte) []bool {
	result := make([]bool, 0, terminalQRTotalCodewords*8+terminalQRRemainderBits)
	for _, value := range codewords {
		for bit := 7; bit >= 0; bit-- {
			result = append(result, value&(1<<bit) != 0)
		}
	}
	return append(result, false, false, false)
}

func terminalQRBestMatrix(data []bool) [][]bool {
	var best [][]bool
	bestPenalty := math.MaxInt
	for mask := 0; mask < 8; mask++ {
		matrix, used := terminalQRFunctionPatterns(mask)
		terminalQRPlaceData(matrix, used, data, mask)
		penalty := terminalQRPenalty(matrix)
		if penalty < bestPenalty {
			best = matrix
			bestPenalty = penalty
		}
	}
	return best
}

func terminalQRFunctionPatterns(mask int) ([][]bool, [][]bool) {
	matrix := terminalQRBoolMatrix(terminalQRVersionSize)
	used := terminalQRBoolMatrix(terminalQRVersionSize)
	terminalQRDrawFinder(matrix, used, 3, 3)
	terminalQRDrawFinder(matrix, used, terminalQRVersionSize-4, 3)
	terminalQRDrawFinder(matrix, used, 3, terminalQRVersionSize-4)
	lastCenter := len(terminalQRAlignmentCenters) - 1
	for centerYIndex, centerY := range terminalQRAlignmentCenters {
		for centerXIndex, centerX := range terminalQRAlignmentCenters {
			if (centerXIndex == 0 && (centerYIndex == 0 || centerYIndex == lastCenter)) ||
				(centerXIndex == lastCenter && centerYIndex == 0) {
				continue
			}
			terminalQRDrawAlignment(matrix, used, centerX, centerY)
		}
	}
	for coordinate := 8; coordinate < terminalQRVersionSize-8; coordinate++ {
		terminalQRSetFunction(matrix, used, 6, coordinate, coordinate%2 == 0)
		terminalQRSetFunction(matrix, used, coordinate, 6, coordinate%2 == 0)
	}
	terminalQRDrawFormat(matrix, used, mask)
	terminalQRDrawVersion(matrix, used)
	return matrix, used
}

func terminalQRFunctionMap() [][]bool {
	_, used := terminalQRFunctionPatterns(0)
	return used
}

func terminalQRDrawFinder(matrix, used [][]bool, centerX, centerY int) {
	for offsetY := -4; offsetY <= 4; offsetY++ {
		for offsetX := -4; offsetX <= 4; offsetX++ {
			x := centerX + offsetX
			y := centerY + offsetY
			if x < 0 || y < 0 || x >= terminalQRVersionSize || y >= terminalQRVersionSize {
				continue
			}
			distance := max(abs(offsetX), abs(offsetY))
			terminalQRSetFunction(matrix, used, x, y, distance != 2 && distance != 4)
		}
	}
}

func terminalQRDrawAlignment(matrix, used [][]bool, centerX, centerY int) {
	for offsetY := -2; offsetY <= 2; offsetY++ {
		for offsetX := -2; offsetX <= 2; offsetX++ {
			distance := max(abs(offsetX), abs(offsetY))
			terminalQRSetFunction(
				matrix,
				used,
				centerX+offsetX,
				centerY+offsetY,
				distance != 1,
			)
		}
	}
}

func terminalQRDrawFormat(matrix, used [][]bool, mask int) {
	data := (0b01 << 3) | mask
	bits := (data << 10) | terminalQRBCHRemainder(data<<10, 0x537)
	bits ^= 0x5412
	for index := 0; index <= 5; index++ {
		terminalQRSetFunction(matrix, used, 8, index, terminalQRBit(bits, index))
	}
	terminalQRSetFunction(matrix, used, 8, 7, terminalQRBit(bits, 6))
	terminalQRSetFunction(matrix, used, 8, 8, terminalQRBit(bits, 7))
	terminalQRSetFunction(matrix, used, 7, 8, terminalQRBit(bits, 8))
	for index := 9; index < 15; index++ {
		terminalQRSetFunction(matrix, used, 14-index, 8, terminalQRBit(bits, index))
	}
	for index := 0; index <= 7; index++ {
		terminalQRSetFunction(
			matrix,
			used,
			terminalQRVersionSize-1-index,
			8,
			terminalQRBit(bits, index),
		)
	}
	for index := 8; index < 15; index++ {
		terminalQRSetFunction(
			matrix,
			used,
			8,
			terminalQRVersionSize-15+index,
			terminalQRBit(bits, index),
		)
	}
	terminalQRSetFunction(matrix, used, 8, terminalQRVersionSize-8, true)
}

func terminalQRDrawVersion(matrix, used [][]bool) {
	bits := (terminalQRVersion << 12) |
		terminalQRBCHRemainder(terminalQRVersion<<12, 0x1f25)
	for index := 0; index < 18; index++ {
		value := terminalQRBit(bits, index)
		column := terminalQRVersionSize - 11 + index%3
		row := index / 3
		terminalQRSetFunction(matrix, used, column, row, value)
		terminalQRSetFunction(matrix, used, row, column, value)
	}
}

func terminalQRPlaceData(matrix, used [][]bool, data []bool, mask int) {
	bitIndex := 0
	upward := true
	for right := terminalQRVersionSize - 1; right >= 1; right -= 2 {
		if right == 6 {
			right--
		}
		for vertical := 0; vertical < terminalQRVersionSize; vertical++ {
			row := vertical
			if upward {
				row = terminalQRVersionSize - 1 - vertical
			}
			for offset := 0; offset < 2; offset++ {
				column := right - offset
				if used[row][column] {
					continue
				}
				value := false
				if bitIndex < len(data) {
					value = data[bitIndex]
					bitIndex++
				}
				if terminalQRMaskApplies(mask, column, row) {
					value = !value
				}
				matrix[row][column] = value
			}
		}
		upward = !upward
	}
}

func terminalQRMaskApplies(mask, column, row int) bool {
	switch mask {
	case 0:
		return (column+row)%2 == 0
	case 1:
		return row%2 == 0
	case 2:
		return column%3 == 0
	case 3:
		return (column+row)%3 == 0
	case 4:
		return (column/3+row/2)%2 == 0
	case 5:
		return column*row%2+column*row%3 == 0
	case 6:
		return (column*row%2+column*row%3)%2 == 0
	case 7:
		return ((column+row)%2+column*row%3)%2 == 0
	default:
		return false
	}
}

func terminalQRPenalty(matrix [][]bool) int {
	penalty := 0
	size := len(matrix)
	for row := 0; row < size; row++ {
		penalty += terminalQRRunPenalty(matrix[row])
		column := make([]bool, size)
		for index := range column {
			column[index] = matrix[index][row]
		}
		penalty += terminalQRRunPenalty(column)
	}
	for row := 0; row < size-1; row++ {
		for column := 0; column < size-1; column++ {
			value := matrix[row][column]
			if matrix[row][column+1] == value &&
				matrix[row+1][column] == value &&
				matrix[row+1][column+1] == value {
				penalty += 3
			}
		}
	}
	dark := 0
	for row := range matrix {
		for _, value := range matrix[row] {
			if value {
				dark++
			}
		}
	}
	total := size * size
	deviation := abs(dark*20-total*10) / total
	return penalty + deviation*10
}

func terminalQRRunPenalty(line []bool) int {
	penalty := 0
	runLength := 0
	last := false
	for index, value := range line {
		if index == 0 || value != last {
			last = value
			runLength = 1
		} else {
			runLength++
			if runLength == 5 {
				penalty += 3
			} else if runLength > 5 {
				penalty++
			}
		}
		if index >= 10 {
			pattern := line[index-10 : index+1]
			if terminalQRFinderLike(pattern) {
				penalty += 40
			}
		}
	}
	return penalty
}

func terminalQRFinderLike(pattern []bool) bool {
	return (!pattern[0] && !pattern[1] && !pattern[2] && !pattern[3] &&
		pattern[4] && !pattern[5] && pattern[6] && pattern[7] && pattern[8] &&
		!pattern[9] && pattern[10]) ||
		(pattern[0] && !pattern[1] && pattern[2] && pattern[3] && pattern[4] &&
			!pattern[5] && pattern[6] && !pattern[7] && !pattern[8] && !pattern[9] &&
			!pattern[10])
}

func terminalQRRender(matrix [][]bool) []byte {
	size := len(matrix) + terminalQRQuietZone*2
	withQuietZone := terminalQRBoolMatrix(size)
	for row := range matrix {
		copy(
			withQuietZone[row+terminalQRQuietZone][terminalQRQuietZone:],
			matrix[row],
		)
	}
	var output bytes.Buffer
	output.Grow(MaxTerminalQRBytes)
	for row := 0; row < size; row += 2 {
		for column := 0; column < size; column++ {
			top := withQuietZone[row][column]
			bottom := row+1 < size && withQuietZone[row+1][column]
			switch {
			case top && bottom:
				output.WriteRune('█')
			case top:
				output.WriteRune('▀')
			case bottom:
				output.WriteRune('▄')
			default:
				output.WriteByte(' ')
			}
		}
		output.WriteByte('\n')
	}
	return output.Bytes()
}

func terminalQRReedSolomon(data []byte, degree int) []byte {
	divisor := make([]byte, degree)
	divisor[degree-1] = 1
	root := byte(1)
	for range degree {
		for index := range divisor {
			divisor[index] = terminalQRGFMultiply(divisor[index], root)
			if index+1 < len(divisor) {
				divisor[index] ^= divisor[index+1]
			}
		}
		root = terminalQRGFMultiply(root, 2)
	}
	remainder := make([]byte, degree)
	for _, value := range data {
		factor := value ^ remainder[0]
		copy(remainder, remainder[1:])
		remainder[len(remainder)-1] = 0
		for index := range remainder {
			remainder[index] ^= terminalQRGFMultiply(divisor[index], factor)
		}
	}
	clear(divisor)
	return remainder
}

func terminalQRGFMultiply(left, right byte) byte {
	result := byte(0)
	for range 8 {
		if right&1 != 0 {
			result ^= left
		}
		high := left&0x80 != 0
		left <<= 1
		if high {
			left ^= 0x1d
		}
		right >>= 1
	}
	return result
}

func terminalQRBCHRemainder(value, polynomial int) int {
	for terminalQRBitLength(value) >= terminalQRBitLength(polynomial) {
		value ^= polynomial << (terminalQRBitLength(value) - terminalQRBitLength(polynomial))
	}
	return value
}

func terminalQRBitLength(value int) int {
	length := 0
	for value != 0 {
		length++
		value >>= 1
	}
	return length
}

func terminalQRBit(value, index int) bool {
	return value&(1<<index) != 0
}

func terminalQRSetFunction(matrix, used [][]bool, column, row int, value bool) {
	matrix[row][column] = value
	used[row][column] = true
}

func terminalQRBoolMatrix(size int) [][]bool {
	result := make([][]bool, size)
	for row := range result {
		result[row] = make([]bool, size)
	}
	return result
}

func abs(value int) int {
	if value < 0 {
		return -value
	}
	return value
}

type terminalQRBlock struct {
	data []byte
	ecc  []byte
}

type terminalQRBitWriter struct {
	bytes     []byte
	bitLength int
}

func (writer *terminalQRBitWriter) append(value uint32, count int) {
	for bit := count - 1; bit >= 0; bit-- {
		if writer.bitLength%8 == 0 {
			writer.bytes = append(writer.bytes, 0)
		}
		if value&(1<<bit) != 0 {
			writer.bytes[len(writer.bytes)-1] |= 1 << (7 - writer.bitLength%8)
		}
		writer.bitLength++
	}
}
