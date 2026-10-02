package main

import (
	"math"

	pb "demo/proto"
)

// int64Specials は int64 フィールドに意図的に混ぜる値。
// 小さい値・0・負数・2^53 超・MaxInt64・MinInt64 を含む。
var int64Specials = []int64{
	0,
	1,
	-1,
	42,
	9007199254740993,  // 2^53 + 1 (double では正確に表せない)
	math.MaxInt64,     // 9223372036854775807
	math.MinInt64,     // -9223372036854775808
	-9007199254740993, // -(2^53 + 1)
	1234567890123,
}

// stringSpecials は文字列フィールドに混ぜる値。
// 数字だけの文字列・改行・ダブルクォート・日本語・HTML 系記号を含む。
var stringSpecials = []string{
	"12345",
	"a\nb",
	`say "hi"`,
	"日本語テキスト",
	"-9223372036854775808",
	"0",
	"<tag> & a=b 'q'",
	"",
	"plain",
}

const JobID int64 = 9007199254740993 // 2^53 + 1

func pickInt(i int) int64     { return int64Specials[i%len(int64Specials)] }
func pickString(i int) string { return stringSpecials[i%len(stringSpecials)] }

// buildObjectLog は i 番目の ObjectLog を決定的に生成する(乱数不使用)。
// Event の通し番号 g = i*eventsPerObject + j を元に値を選ぶ。
// payload の値は k = g/3 で選ぶ(特殊値リストの長さ 9 が oneof の周期 3 の倍数なので、
// g をそのまま使うと各フィールドに 9 種中 3 種しか現れない)。
func buildObjectLog(i int, eventsPerObject int) *pb.ObjectLog {
	ol := &pb.ObjectLog{
		ObjectId: pickInt(i),
	}
	// 3件に1件は現実的なミリ秒タイムスタンプ、それ以外は特殊値
	if i%3 == 0 {
		ol.Timestamp = 1700000000000 + int64(i)
	} else {
		ol.Timestamp = pickInt(i + 4)
	}
	for j := 0; j < eventsPerObject; j++ {
		g := i*eventsPerObject + j
		k := g / 3
		ev := &pb.Event{
			EventId:   int64(g), // g=0 のとき 0
			EventType: pickInt(g + 1),
		}
		// oneof は comm_event / exec_event / 未設定 を交互に
		switch g % 3 {
		case 0:
			ev.Payload = &pb.Event_CommEvent{CommEvent: &pb.CommEvent{
				FromId: pickInt(k + 2),
				ToId:   pickInt(k + 3),
				Body:   pickString(k),
			}}
		case 1:
			ev.Payload = &pb.Event_ExecEvent{ExecEvent: &pb.ExecEvent{
				ExecId:  pickInt(k + 5),
				Command: pickString(k + 1),
				Result:  pickString(k + 2),
			}}
		default:
			// payload 未設定
		}
		ol.Events = append(ol.Events, ev)
	}
	return ol
}

func buildResult() *pb.Result {
	return &pb.Result{
		ExitCode: math.MinInt64,
		Output:   "9223372036854775807", // 数字だけの文字列(数値化してはいけない)
	}
}

func buildSimLog(req *pb.GetSimLogRequest) *pb.SimLog {
	sl := &pb.SimLog{JobId: JobID}
	for i := 0; i < int(req.GetObjectCount()); i++ {
		sl.Logs = append(sl.Logs, buildObjectLog(i, int(req.GetEventsPerObject())))
	}
	if req.GetIncludeResult() {
		sl.Result = buildResult()
	}
	return sl
}
