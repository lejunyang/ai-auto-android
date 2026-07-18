package mcpserver

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
			}
		]
	}`)
}

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
				"required":["device","action","text"],
				"properties":{
					"device":` + deviceSchema() + `,
					"action":{"const":"ui.setText"},
					"text":{"type":"string","minLength":1,"maxLength":10000,"pattern":"^[-A-Za-z0-9 @._+,:/]*$"}
				},
				"additionalProperties":false
			},
			{
				"type":"object",
				"required":["device","action","key"],
				"properties":{
					"device":` + deviceSchema() + `,
					"action":{"const":"ui.pressKey"},
					"key":{"enum":["BACK","HOME","RECENTS","ENTER","TAB","ESCAPE","SPACE","DELETE","FORWARD_DEL","DPAD_UP","DPAD_DOWN","DPAD_LEFT","DPAD_RIGHT","DPAD_CENTER","VOLUME_UP","VOLUME_DOWN","VOLUME_MUTE"]}
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
			},
			{
				"type":"object",
				"required":["device","action","package"],
				"properties":{
					"device":` + deviceSchema() + `,
					"action":{"const":"app.stop"},
					"package":` + packageSchema() + `
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
			"kind":{"enum":["screenshot","hierarchy","semantic","recordings"]},
			"format":{"enum":["png","uiautomator-xml","accessibility-tree","recording-list"]},
			"sizeBytes":{"type":"integer","minimum":1},
			"sha256":{"type":"string","pattern":"^[0-9a-f]{64}$"},
			"xml":{"type":"string"},
			"snapshot":{"$ref":"#/$defs/snapshot"},
			"recordings":{"type":"array","items":{"$ref":"#/$defs/recording"}},
			"count":{"type":"integer","minimum":0}
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
			}
		}
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
