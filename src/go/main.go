// 動作テスト用の gRPC サーバ(SimService)。Java 側の統合テスト・手動確認のための固定データを返す。
//
//	go run . [-port 50051]
//
// データは object_count / events_per_object から決定的に生成する(乱数なし)。
// int64 には 0・負数・2^53+1・MaxInt64・MinInt64、文字列には数字だけ・改行・引用符・日本語を混ぜている。
package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"math"
	"net"

	pb "demo/proto"

	"google.golang.org/grpc"
)

var int64Values = []int64{0, 1, -1, 42, 9007199254740993, math.MaxInt64, math.MinInt64, -9007199254740993, 1234567890123}

var stringValues = []string{"12345", "a\nb", `say "hi"`, "日本語テキスト", "-9223372036854775808", "0", "<tag> & a=b 'q'", "", "plain"}

func pickInt(i int) int64     { return int64Values[i%len(int64Values)] }
func pickString(i int) string { return stringValues[i%len(stringValues)] }

func buildObjectLog(i, eventsPerObject int) *pb.ObjectLog {
	ol := &pb.ObjectLog{Timestamp: 1700000000000 + int64(i), ObjectId: pickInt(i)}
	for j := 0; j < eventsPerObject; j++ {
		g := i*eventsPerObject + j
		k := g / 3
		ev := &pb.Event{EventId: int64(g), EventType: pickInt(g + 1)}
		switch g % 3 { // comm_event / exec_event / 未設定 を交互に
		case 0:
			ev.Payload = &pb.Event_CommEvent{CommEvent: &pb.CommEvent{FromId: pickInt(k + 2), ToId: pickInt(k + 3), Body: pickString(k)}}
		case 1:
			ev.Payload = &pb.Event_ExecEvent{ExecEvent: &pb.ExecEvent{ExecId: pickInt(k + 5), Command: pickString(k + 1), Result: pickString(k + 2)}}
		}
		ol.Events = append(ol.Events, ev)
	}
	return ol
}

type server struct {
	pb.UnimplementedSimServiceServer
}

func (server) GetSimLog(_ context.Context, req *pb.GetSimLogRequest) (*pb.SimLog, error) {
	log.Printf("GetSimLog %v", req)
	sl := &pb.SimLog{JobId: 9007199254740993}
	for i := 0; i < int(req.GetObjectCount()); i++ {
		sl.Logs = append(sl.Logs, buildObjectLog(i, int(req.GetEventsPerObject())))
	}
	if req.GetIncludeResult() {
		sl.Result = &pb.Result{ExitCode: math.MinInt64, Output: "9223372036854775807"}
	}
	return sl, nil
}

func (server) StreamObjectLogs(req *pb.GetSimLogRequest, stream grpc.ServerStreamingServer[pb.ObjectLog]) error {
	log.Printf("StreamObjectLogs %v", req)
	for i := 0; i < int(req.GetObjectCount()); i++ {
		if err := stream.Send(buildObjectLog(i, int(req.GetEventsPerObject()))); err != nil {
			return err
		}
	}
	return nil
}

func main() {
	port := flag.Int("port", 50051, "listen port")
	flag.Parse()
	lis, err := net.Listen("tcp", fmt.Sprintf(":%d", *port))
	if err != nil {
		log.Fatalf("listen: %v", err)
	}
	s := grpc.NewServer()
	pb.RegisterSimServiceServer(s, server{})
	log.Printf("SimService gRPC server listening on :%d", *port)
	if err := s.Serve(lis); err != nil {
		log.Fatalf("serve: %v", err)
	}
}
