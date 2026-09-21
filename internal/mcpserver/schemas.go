package mcpserver

// 功能用途：本文件集中构造 MCP 工具输入输出 JSON Schema，保持附加字段默认拒绝。

import "encoding/json"

func devicesListSchema() json.RawMessage {
	return json.RawMessage(`{
		"type":"object",
		"properties":{},
		"additionalProperties":false
	}`)
}

func deviceGetSchema() json.RawMessage {
	return objectSchema(
		`"required":["device"]`,
		`"properties":{"device":`+deviceSchema()+`}`,
	)
}

func observeSchema() json.RawMessage {
	return json.RawMessage(`{
		"type":"object",
		"oneOf":[
			{
				"type":"object",
				"required":["device","kind"],
				"properties":{
					"device":` + deviceSchema() + `,
					"kind":{"const":"screenshot"}
				},
				"additionalProperties":false
			},
			{
				"type":"object",
				"required":["device","kind"],
				"properties":{
					"device":` + deviceSchema() + `,
					"kind":{"const":"hierarchy"}
				},
				"additionalProperties":false
			},
			{
				"type":"object",
				"required":["device","kind"],
				"properties":{
					"device":` + deviceSchema() + `,
					"kind":{"const":"semantic"},
					"targetPackage":` + packageSchema() + `,
					"maxDepth":{"type":"integer","minimum":1,"maximum":100}
				},
				"additionalProperties":false
			},
			{
				"type":"object",
				"required":["device","kind"],
				"properties":{
					"device":` + deviceSchema() + `,
					"kind":{"const":"recordings"}
				},
				"additionalProperties":false
			},
			{
				"type":"object",
				"required":["device","kind"],
				"properties":{
					"device":` + deviceSchema() + `,
					"kind":{"const":"foreground"}
				},
				"additionalProperties":false
			},
			{
				"type":"object",
				"required":["device","kind"],
				"properties":{
					"device":` + deviceSchema() + `,
					"kind":{"const":"apps"},
					"includeSystem":{"type":"boolean"},
					"withDetails":{"type":"boolean"}
				},
				"additionalProperties":false
			},
			{
				"type":"object",
				"required":["device","kind"],
				"properties":{
					"device":` + deviceSchema() + `,
					"kind":{"const":"windows"},
					"targetPackage":` + packageSchema() + `,
					"maxDepth":{"type":"integer","minimum":1,"maximum":100}
				},
				"additionalProperties":false
			},
			{
				"type":"object",
				"required":["device","kind"],
				"properties":{
					"device":` + deviceSchema() + `,
					"kind":{"const":"transientMessages"},
					"toastsOnly":{"type":"boolean"}
				},
				"additionalProperties":false
			}
		]
	}`)
}

// predicateSchema 描述界面等待与断言的入参。
//
// 刻意不接受任何坐标字段：协议层的 target 含 recordedBounds、relativePoint 与
// normalizedScreenPoint，但坐标判据在页面重排后会静默命中错误元素，
// 而断言的价值恰在于捕获这种偏离。这里只按可描述属性匹配。
//
// 用 oneOf 按 kind 分支而非一个平铺对象：node 类需要选择器、package 类需要 expected、
// uiStable 只接受 stable 运算符，平铺后这些约束只能靠运行时校验，
// 模型拿不到结构化提示。
func predicateSchema() json.RawMessage {
	return json.RawMessage(`{
		"type":"object",
		"oneOf":[
			{
				"type":"object",
				"required":["device","kind","operator"],
				"properties":{
					"device":` + deviceSchema() + `,
					"kind":{"enum":["node","text"]},
					"operator":{"enum":["exists","notExists","equals","contains","matches"]},
					"resourceId":{"type":"string","minLength":1,"maxLength":512},
					"text":{"type":"string","minLength":1,"maxLength":1024},
					"description":{"type":"string","minLength":1,"maxLength":1024},
					"className":{"type":"string","minLength":1,"maxLength":512},
					"package":` + packageSchema() + `,
					"expected":{"type":"string","maxLength":1024},
					"timeoutMs":{"type":"integer","minimum":1,"maximum":60000}
				},
				"anyOf":[
					{"required":["resourceId"]},
					{"required":["text"]},
					{"required":["description"]},
					{"required":["className"]},
					{"required":["package"]}
				],
				"additionalProperties":false
			},
			{
				"type":"object",
				"required":["device","kind","operator","expected"],
				"properties":{
					"device":` + deviceSchema() + `,
					"kind":{"enum":["package","window"]},
					"operator":{"enum":["equals","contains","matches"]},
					"expected":{"type":"string","minLength":1,"maxLength":1024},
					"timeoutMs":{"type":"integer","minimum":1,"maximum":60000}
				},
				"additionalProperties":false
			},
			{
				"type":"object",
				"required":["device","kind","operator"],
				"properties":{
					"device":` + deviceSchema() + `,
					"kind":{"const":"uiStable"},
					"operator":{"const":"stable"},
					"stableDurationMs":{"type":"integer","minimum":100,"maximum":30000},
					"timeoutMs":{"type":"integer","minimum":1,"maximum":60000}
				},
				"additionalProperties":false
			},
			{
				"type":"object",
				"required":["device","kind","operator"],
				"properties":{
					"device":` + deviceSchema() + `,
					"kind":{"enum":["newWindow","scrollFinished"]},
					"operator":{"const":"occurred"},
					"stableDurationMs":{"type":"integer","minimum":100,"maximum":30000},
					"timeoutMs":{"type":"integer","minimum":1,"maximum":60000}
				},
				"additionalProperties":false
			}
		]
	}`)
}

// predicateOutputSchema 描述等待与断言的返回值。
//
// 除 satisfied 外还返回 waitedMs、polls、matchCount 与观察到的实际值：
// 只回一个布尔值时，调用方无法区分"立刻不成立"与"等到超时才放弃"，
// 也无法据此纠正——而纠正正是这两个工具存在的理由。
func predicateOutputSchema() json.RawMessage {
	return json.RawMessage(`{
		"type":"object",
		"required":["device","kind","operator","satisfied","waitedMs","polls"],
		"properties":{
			"device":` + deviceSchema() + `,
			"kind":{"enum":["node","text","package","window","uiStable","newWindow","scrollFinished"]},
			"operator":{"enum":["exists","notExists","equals","contains","matches","stable","occurred"]},
			"satisfied":{"type":"boolean"},
			"waitedMs":{"type":"integer","minimum":0},
			"polls":{"type":"integer","minimum":0},
			"matchCount":{"type":"integer","minimum":0},
			"actualText":{"type":"string"},
			"actualPackage":{"type":"string"}
		},
		"additionalProperties":false
	}`)
}

// bridgeSetTextSchema 描述桥侧文本输入的入参。
//
// 与 actionSchema 的 ui.setText 分支刻意不同：那里有 ASCII 正则，
// 因为它落到 adb shell input text；这里没有字符集限制，
// 因为无障碍 ACTION_SET_TEXT 接受任意 CharSequence。
//
// 三个定位字段用 anyOf 约束"至少填一个"：全空会匹配到任意可编辑节点，
// 写入位置将不可预期。
func bridgeSetTextSchema() json.RawMessage {
	return json.RawMessage(`{
		"type":"object",
		"required":["device","text"],
		"properties":{
			"device":` + deviceSchema() + `,
			"text":{"type":"string","minLength":1,"maxLength":10000},
			"package":` + packageSchema() + `,
			"resourceId":{"type":"string","minLength":1,"maxLength":512},
			"description":{"type":"string","minLength":1,"maxLength":1024},
			"matchText":{"type":"string","minLength":1,"maxLength":1024}
		},
		"anyOf":[
			{"required":["resourceId"]},
			{"required":["description"]},
			{"required":["matchText"]}
		],
		"additionalProperties":false
	}`)
}

// bridgeSetTextOutputSchema 描述桥侧文本写入的返回值。
//
// 三个字段都可选：App 侧按执行路径决定回哪些证据，
// 例如直接命中唯一节点时不必回完整路径。
func bridgeSetTextOutputSchema() json.RawMessage {
	return json.RawMessage(`{
		"type":"object",
		"properties":{
			"route":{"type":"string","maxLength":128},
			"matchedPath":{"type":"array","maxItems":128,"items":{"type":"integer"}},
			"matchScore":{"type":"number","minimum":0,"maximum":1}
		},
		"additionalProperties":false
	}`)
}

// screenRecordSchema 描述屏幕录制工具的入参。
//
// 单独成工具而不作为 observe 的一个 kind：录制会阻塞数秒到数分钟，
// 与其余观察的即时返回语义差别太大，混在一起会让调用方难以设置超时。
func screenRecordSchema() json.RawMessage {
	return json.RawMessage(`{
		"type":"object",
		"required":["device","seconds"],
		"properties":{
			"device":` + deviceSchema() + `,
			"seconds":{"type":"integer","minimum":1,"maximum":180},
			"width":{"type":"integer","minimum":1,"maximum":100000},
			"height":{"type":"integer","minimum":1,"maximum":100000},
			"bitRate":{"type":"integer","minimum":1}
		},
		"additionalProperties":false
	}`)
}

// actionSchema 描述模型可发起的直连 ADB 动作。
//
// 分支集合必须与 risk.go 的 requireModelActionAllowed 白名单一致。此前这里多声明
// 了 app.stop：schema 允许模型构造该调用，运行时却必然被风险门拒绝，
// 模型只能靠试错发现这条路不通。声明面与执行面不一致本身就是缺陷，
// 因此移除该分支而不是放宽风险门——风险门是安全权威，
// 放宽它等于扩大模型可发起的动作面。需要停止应用时走 CLI 的 `aactl action stop`。
//
// ui.pressKey 的键枚举以 protocol/schema/v1/action.schema.json 为单一权威来源，
// 与 internal/adb 的 allowedKeys 逐项一致，由 presskey_consistency_test.go 守护。
func actionSchema() json.RawMessage {
	return json.RawMessage(`{
		"type":"object",
		"oneOf":[
			{
				"type":"object",
				"required":["device","action","x","y"],
				"properties":{
					"device":` + deviceSchema() + `,
					"action":{"const":"ui.tap"},
					"x":` + coordinateSchema() + `,
					"y":` + coordinateSchema() + `
				},
				"additionalProperties":false
			},
			{
				"type":"object",
				"required":["device","action","startX","startY","endX","endY"],
				"properties":{
					"device":` + deviceSchema() + `,
					"action":{"const":"ui.swipe"},
					"startX":` + coordinateSchema() + `,
					"startY":` + coordinateSchema() + `,
					"endX":` + coordinateSchema() + `,
					"endY":` + coordinateSchema() + `,
					"durationMs":{"type":"integer","minimum":1,"maximum":60000}
				},
				"additionalProperties":false
			},
			{
				"type":"object",
				"required":["device","action","x","y"],
				"properties":{
					"device":` + deviceSchema() + `,
					"action":{"const":"ui.longClick"},
					"x":` + coordinateSchema() + `,
					"y":` + coordinateSchema() + `,
					"durationMs":{"type":"integer","minimum":300,"maximum":10000}
				},
				"additionalProperties":false
			},
			{
				"type":"object",
				"required":["device","action","direction"],
				"properties":{
					"device":` + deviceSchema() + `,
					"action":{"const":"ui.scroll"},
					"direction":{"enum":["up","down","left","right","forward","backward"]},
					"amount":{"type":"number","minimum":0.05,"maximum":0.9}
				},
				"additionalProperties":false
			},
			{
				"type":"object",
				"required":["device","action","text"],
				"properties":{
					"device":` + deviceSchema() + `,
					"action":{"const":"ui.setText"},
					"text":{
						"type":"string",
						"minLength":1,
						"maxLength":10000,
						"pattern":"^[-A-Za-z0-9 @._+,:/]*$",
						"description":"ASCII only, because this action goes through adb shell input text whose escaping is reliable for ASCII alone. For Chinese or any other non-ASCII text call android_bridge_set_text instead: it writes through the accessibility service and accepts any Unicode."
					}
				},
				"additionalProperties":false
			},
			{
				"type":"object",
				"required":["device","action","key"],
				"properties":{
					"device":` + deviceSchema() + `,
					"action":{"const":"ui.pressKey"},
					"key":{"enum":["BACK","HOME","RECENTS","ENTER","TAB","ESCAPE","SPACE","DELETE","FORWARD_DEL","DPAD_UP","DPAD_DOWN","DPAD_LEFT","DPAD_RIGHT","DPAD_CENTER","MOVE_HOME","MOVE_END","PAGE_UP","PAGE_DOWN","VOLUME_UP","VOLUME_DOWN","VOLUME_MUTE"]}
				},
				"additionalProperties":false
			},
			{
				"type":"object",
				"required":["device","action","package"],
				"properties":{
					"device":` + deviceSchema() + `,
					"action":{"const":"app.launch"},
					"package":` + packageSchema() + `,
					"activity":{"type":"string","minLength":1,"maxLength":512,"pattern":"^[A-Za-z0-9_./]+$"}
				},
				"additionalProperties":false
			}
		]
	}`)
}

func observeOutputSchema() json.RawMessage {
	return json.RawMessage(`{
		"type":"object",
		"required":["device","kind","format"],
		"properties":{
			"device":` + deviceSchema() + `,
			"kind":{"enum":["screenshot","hierarchy","semantic","recordings","foreground","apps","windows","transientMessages"]},
			"format":{"enum":["png","uiautomator-xml","accessibility-tree","recording-list","foreground","app-list","window-list","transient-message-list"]},
			"sizeBytes":{"type":"integer","minimum":1},
			"sha256":{"type":"string","pattern":"^[0-9a-f]{64}$"},
			"xml":{"type":"string"},
			"snapshot":{"$ref":"#/$defs/snapshot"},
			"recordings":{"type":"array","items":{"$ref":"#/$defs/recording"}},
			"count":{"type":"integer","minimum":0},
			"foreground":{"$ref":"#/$defs/foreground"},
			"apps":{"type":"array","items":{"$ref":"#/$defs/app"}},
			"includesSystem":{"type":"boolean"},
			"detailsIncluded":{"type":"boolean"},
			"windows":{"type":"object"},
			"messages":{"type":"object"}
		},
		"additionalProperties":false,
		"$defs":{
			"snapshot":{
				"type":"object",
				"required":["root","maxDepth"],
				"properties":{
					"root":{"$ref":"#/$defs/node"},
					"maxDepth":{"type":"integer","minimum":1,"maximum":100}
				},
				"additionalProperties":false
			},
			"node":{
				"type":"object",
				"required":["bounds","actions","state","children"],
				"properties":{
					"packageName":{"type":"string"},
					"className":{"type":"string"},
					"resourceId":{"type":"string"},
					"text":{"type":"string"},
					"contentDescription":{"type":"string"},
					"bounds":{
						"type":"object",
						"required":["left","top","right","bottom"],
						"properties":{
							"left":{"type":"integer"},
							"top":{"type":"integer"},
							"right":{"type":"integer"},
							"bottom":{"type":"integer"}
						},
						"additionalProperties":false
					},
					"actions":{"type":"array","items":{"type":"string"}},
					"state":{
						"type":"object",
						"required":["checkable","checked","clickable","enabled","editable","focusable","focused","longClickable","password","scrollable","selected","visibleToUser","sensitive"],
						"properties":{
							"checkable":{"type":"boolean"},
							"checked":{"type":"boolean"},
							"clickable":{"type":"boolean"},
							"enabled":{"type":"boolean"},
							"editable":{"type":"boolean"},
							"focusable":{"type":"boolean"},
							"focused":{"type":"boolean"},
							"longClickable":{"type":"boolean"},
							"password":{"type":"boolean"},
							"scrollable":{"type":"boolean"},
							"selected":{"type":"boolean"},
							"visibleToUser":{"type":"boolean"},
							"sensitive":{"type":"boolean"}
						},
						"additionalProperties":false
					},
					"children":{"type":"array","items":{"$ref":"#/$defs/node"}}
				},
				"additionalProperties":false
			},
			"recording":{
				"type":"object",
				"required":["id","name","targetPackages","createdAt","stepCount","requirements"],
				"properties":{
					"id":{"type":"string","format":"uuid"},
					"name":{"type":"string","minLength":1,"maxLength":128},
					"targetPackages":{
						"type":"array",
						"minItems":1,
						"maxItems":32,
						"uniqueItems":true,
						"items":` + packageSchema() + `
					},
					"createdAt":{"type":"string","format":"date-time"},
					"stepCount":{"type":"integer","minimum":1,"maximum":10000},
					"requirements":{
						"type":"object",
						"required":["minApiLevel","capabilities"],
						"properties":{
							"minApiLevel":{"type":"integer","minimum":30,"maximum":1000},
							"capabilities":{
								"type":"array",
								"maxItems":64,
								"uniqueItems":true,
								"items":{"type":"string","minLength":1,"maxLength":128}
							}
						},
						"additionalProperties":false
					}
				},
				"additionalProperties":false
			},
			"foreground":{
				"type":"object",
				"required":["package","source"],
				"properties":{
					"package":` + packageSchema() + `,
					"activity":{"type":"string","maxLength":512},
					"source":{"enum":["resumedActivity","currentFocus"]}
				},
				"additionalProperties":false
			},
			"app":{
				"type":"object",
				"required":["package","system"],
				"properties":{
					"package":` + packageSchema() + `,
					"apkPath":{"type":"string","maxLength":1024},
					"versionName":{"type":"string","maxLength":256},
					"versionCode":{"type":"integer","minimum":0},
					"system":{"type":"boolean"}
				},
				"additionalProperties":false
			}
		}
	}`)
}

// screenRecordOutputSchema 描述录屏工具的返回值。
//
// 刻意不返回视频字节：单次录制可达数十 MB，塞进 MCP 响应会超出多数客户端的
// 消息上限。模型拿到摘要即可判断录制是否成功，视频本体由 CLI 侧写文件获取。
func screenRecordOutputSchema() json.RawMessage {
	return json.RawMessage(`{
		"type":"object",
		"required":["device","format","sizeBytes","sha256","durationSeconds"],
		"properties":{
			"device":` + deviceSchema() + `,
			"format":{"const":"mp4"},
			"sizeBytes":{"type":"integer","minimum":1},
			"sha256":{"type":"string","pattern":"^[0-9a-f]{64}$"},
			"durationSeconds":{"type":"integer","minimum":1,"maximum":180},
			"width":{"type":"integer","minimum":0},
			"height":{"type":"integer","minimum":0},
			"bitRate":{"type":"integer","minimum":0}
		},
		"additionalProperties":false
	}`)
}

func recordingReplaySchema() json.RawMessage {
	return objectSchema(
		`"required":["device","scriptId"]`,
		`"properties":{
			"device":`+deviceSchema()+`,
			"scriptId":{"type":"string","format":"uuid"}
		}`,
	)
}

func objectSchema(parts ...string) json.RawMessage {
	value := `{"type":"object",`
	for index, part := range parts {
		if index > 0 {
			value += ","
		}
		value += part
	}
	value += `,"additionalProperties":false}`
	return json.RawMessage(value)
}

func deviceSchema() string {
	return `{"type":"string","minLength":1,"maxLength":255,"pattern":"^[A-Za-z0-9][A-Za-z0-9._:%+\\[\\]-]{0,254}$"}`
}

func packageSchema() string {
	return `{"type":"string","maxLength":255,"pattern":"^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$"}`
}

func coordinateSchema() string {
	return `{"type":"integer","minimum":0,"maximum":100000}`
}

// filePushSchema 描述推送入参。
//
// 没有 force / overwrite 字段，且 additionalProperties:false——覆盖不是可以用
// 参数打开的选项，等价字段在 schema 层就被拒绝。
func filePushSchema() json.RawMessage {
	return json.RawMessage(`{
		"type":"object",
		"required":["device","localPath","remotePath"],
		"properties":{
			"device":` + deviceSchema() + `,
			"localPath":{"type":"string","minLength":1,"maxLength":4096,
				"description":"Path on the machine running this MCP server."},
			"remotePath":{"type":"string","minLength":1,"maxLength":1024,
				"description":"Absolute device path. Must not already exist; the transfer fails instead of overwriting."}
		},
		"additionalProperties":false
	}`)
}

// filePullSchema 描述拉取入参。
//
// localPath 的 description 必须点明落盘位置：模型容易误以为文件回到自己的环境，
// 实际写在运行 MCP 服务的那台机器上。
func filePullSchema() json.RawMessage {
	return json.RawMessage(`{
		"type":"object",
		"required":["device","remotePath","localPath"],
		"properties":{
			"device":` + deviceSchema() + `,
			"remotePath":{"type":"string","minLength":1,"maxLength":1024,
				"description":"Absolute device path to read."},
			"localPath":{"type":"string","minLength":1,"maxLength":4096,
				"description":"Destination on the machine running this MCP server, not the caller environment. Must not already exist; the transfer fails instead of overwriting."}
		},
		"additionalProperties":false
	}`)
}

// fileTransferOutputSchema 描述传输结果。
func fileTransferOutputSchema() json.RawMessage {
	return json.RawMessage(`{
		"type":"object",
		"properties":{
			"device":{"type":"string","maxLength":255},
			"action":{"type":"string","maxLength":64},
			"localPath":{"type":"string","maxLength":4096},
			"remotePath":{"type":"string","maxLength":1024}
		},
		"additionalProperties":false
	}`)
}

// imeShowSchema 描述聚焦输入控件的入参。
//
// 只有 device：聚焦的是系统当前输入焦点，不接受选择器。
func imeShowSchema() json.RawMessage {
	return json.RawMessage(`{
		"type":"object",
		"required":["device"],
		"properties":{"device":` + deviceSchema() + `},
		"additionalProperties":false
	}`)
}

// bridgeImeShowOutputSchema 描述聚焦结果。
//
// 刻意只有 route 一个字段，没有 keyboardVisible 之类的布尔：无障碍查不到键盘
// 状态，声明一个那样的字段就是在承诺一个无法验证的事实。
func bridgeImeShowOutputSchema() json.RawMessage {
	return json.RawMessage(`{
		"type":"object",
		"properties":{"route":{"type":"string","maxLength":128}},
		"additionalProperties":false
	}`)
}

// scrollUntilSchema 描述 ui.scrollUntil 的入参。
//
// 选择器与运算符字段与 predicateSchema 一致；direction 必填，amount 与 maxScrolls
// 可选。不引入坐标——与判据一样只按可描述属性匹配。
func scrollUntilSchema() json.RawMessage {
	return json.RawMessage(`{
		"type":"object",
		"required":["device","kind","operator","direction"],
		"properties":{
			"device":` + deviceSchema() + `,
			"kind":{"enum":["node","text","package","window","uiStable","newWindow","scrollFinished"]},
			"operator":{"enum":["exists","notExists","equals","contains","matches","stable","occurred"]},
			"direction":{"enum":["up","down","left","right","forward","backward"]},
			"resourceId":{"type":"string","minLength":1,"maxLength":512},
			"text":{"type":"string","minLength":1,"maxLength":1024},
			"description":{"type":"string","minLength":1,"maxLength":1024},
			"className":{"type":"string","minLength":1,"maxLength":512},
			"package":` + packageSchema() + `,
			"expected":{"type":"string","maxLength":1024},
			"timeoutMs":{"type":"integer","minimum":1,"maximum":60000},
			"stableDurationMs":{"type":"integer","minimum":100,"maximum":30000},
			"amount":{"type":"number","exclusiveMinimum":0,"maximum":1},
			"maxScrolls":{"type":"integer","minimum":1,"maximum":50}
		},
		"additionalProperties":false
	}`)
}
