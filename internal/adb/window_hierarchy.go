package adb

// 功能用途：严格筛选按显示分组的 UIAutomator 窗口列表，确保不会回退到活动窗口。

import (
	"bytes"
	"encoding/xml"
	"io"
	"strconv"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
)

// 无输出与有效XML中的空显示不同；只说明系统未提供任何窗口，不能据此判断目标不存在。
func windowsUnavailable(id int) error {
	return apperr.New(apperr.CodeWindowsUnavailable, "UIAutomator produced no windows. A common cause is the primary display sleeping while the target uses isolated focus. Wake the primary display, or close and recreate the virtual display with focus=steal (destroys its apps).", false, map[string]any{"displayId": id})
}

func selectDisplayWindows(data []byte, id int) ([]byte, error) {
	invalid := func() ([]byte, error) {
		return nil, apperr.New(apperr.CodeActionFailed, "Invalid or truncated UIAutomator display-window XML.", false, nil)
	}
	if len(data) > maxHierarchyOutput {
		return nil, apperr.New(apperr.CodeMessageTooLarge, "UI hierarchy exceeds its byte budget.", false, nil)
	}
	if len(bytes.TrimSpace(data)) == 0 {
		return nil, windowsUnavailable(id)
	}
	decoder := xml.NewDecoder(bytes.NewReader(data))
	depth := 0
	rootSeen := false
	seen := map[int]bool{}
	currentID := -1
	start := 0
	windowCount := 0
	selectedWindowCount := 0
	var selected []byte
	for {
		before := int(decoder.InputOffset())
		token, err := decoder.Token()
		if err == io.EOF {
			if !rootSeen || depth != 0 {
				return invalid()
			}
			break
		}
		if err != nil {
			return invalid()
		}
		switch v := token.(type) {
		case xml.StartElement:
			depth++
			if depth > 256 || v.Name.Space != "" {
				return invalid()
			}
			if depth == 1 {
				if rootSeen || v.Name.Local != "displays" || len(v.Attr) != 0 {
					return invalid()
				}
				rootSeen = true
			}
			if depth == 2 {
				if v.Name.Local != "display" || len(v.Attr) != 1 || v.Attr[0].Name.Space != "" || v.Attr[0].Name.Local != "id" {
					return invalid()
				}
				number, err := strconv.Atoi(v.Attr[0].Value)
				if err != nil || number < 0 || number > 2147483647 || strconv.Itoa(number) != v.Attr[0].Value || seen[number] {
					return invalid()
				}
				seen[number] = true
				currentID = number
				start = before
				windowCount = 0
			} else if v.Name.Local == "display" {
				return invalid()
			}
			if depth == 3 {
				if v.Name.Local != "window" {
					return invalid()
				}
				windowCount++
			}
		case xml.EndElement:
			if depth == 2 && currentID == id {
				selectedWindowCount = windowCount
				selected = data[start:int(decoder.InputOffset())]
			}
			depth--
		case xml.CharData:
			if depth < 3 && len(bytes.TrimSpace(v)) != 0 {
				return invalid()
			}
		case xml.Directive:
			return invalid()
		}
	}
	if selected == nil || selectedWindowCount == 0 {
		// 归属由service在调用前核对（主屏0除外）；有效XML缺窗口只说明空屏，不说明屏不存在。
		// 必须等完整输入通过校验才分类，不能把损坏尾部误报为空屏或回退到主屏。
		return nil, apperr.New(apperr.CodeDisplayEmpty, "The display has no windows. Launch an application on that display before reading hierarchy.", false, map[string]any{"displayId": id})
	}
	// 完整校验全部输入后只复制目标组，保留真实窗口层次与属性，绝不拼入其它显示。
	result := append([]byte(`<?xml version="1.0" encoding="UTF-8"?><displays>`), selected...)
	result = append(result, []byte("</displays>")...)
	if len(result) > maxHierarchyOutput {
		return nil, apperr.New(apperr.CodeMessageTooLarge, "Filtered UI hierarchy exceeds its byte budget.", false, nil)
	}
	return result, nil
}
