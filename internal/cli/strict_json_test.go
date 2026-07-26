package cli

// 测试用途：验证 stdin strict JSON reader 有界拒绝重复 key、尾随值、非法 UTF-8 与读取错误。

import (
	"bytes"
	"io"
	"strings"
	"testing"
)

func TestReadStrictJSONObjectAcceptsWhitespaceAndCopiesObject(t *testing.T) {
	input := []byte(" \n{\"outer\":{\"key\":1},\"array\":[1,2]}\t ")
	result, err := readStrictJSONObject(bytes.NewReader(input), 1024)
	if err != nil {
		t.Fatalf("readStrictJSONObject() error=%v", err)
	}
	defer clear(result)
	if string(result) != `{"outer":{"key":1},"array":[1,2]}` {
		t.Fatalf("result=%q", result)
	}
	clear(input)
	if string(result) != `{"outer":{"key":1},"array":[1,2]}` {
		t.Fatal("result aliases input buffer")
	}
}

func TestReadStrictJSONObjectRejectsUnsafeInputs(t *testing.T) {
	tests := [][]byte{
		nil,
		[]byte("[]"),
		[]byte(`{"key":1,"key":2}`),
		[]byte(`{"nested":{"key":1,"key":2}}`),
		[]byte("{}{}"),
		[]byte("{} trailing"),
		{0xff, 0xfe},
		[]byte("{\x00}"),
		[]byte("\xc2\xa0{}\xc2\xa0"),
		[]byte(strings.Repeat("x", 11)),
	}
	for index, input := range tests {
		if _, err := readStrictJSONObject(bytes.NewReader(input), 10); err == nil {
			t.Fatalf("case=%d unexpectedly succeeded", index)
		}
	}
}

func TestReadStrictJSONObjectRejectsReaderFailure(t *testing.T) {
	if _, err := readStrictJSONObject(failingStrictReader{}, 1024); err == nil {
		t.Fatal("reader failure unexpectedly succeeded")
	}
}

type failingStrictReader struct{}

func (failingStrictReader) Read([]byte) (int, error) {
	return 0, io.ErrUnexpectedEOF
}
