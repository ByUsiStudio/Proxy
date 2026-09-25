// 参考矩阵生成器：用项目已依赖的 skip2/go-qrcode 生成二维码模块矩阵，
// 用于离线逐模块核对 proxy-server 前端纯 JS 版二维码编码器
// （proxy-server/src/main/resources/static/common/js/qrcode.js）。
//
// 用法: qrverify texts.txt > ref.txt
// texts.txt 每行一个测试文本；输出为「RESULT\t版本」+ 矩阵（0/1，无静默区）+ END 的重复块。
// 采用文件而非管道交换数据：某些沙箱环境禁止子进程通过命名管道捕获输出。
package main

import (
	"bufio"
	"fmt"
	"os"

	qrcode "github.com/skip2/go-qrcode"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "用法: qrverify <texts.txt>")
		os.Exit(2)
	}
	f, err := os.Open(os.Args[1])
	if err != nil {
		fmt.Fprintf(os.Stderr, "打开输入失败: %v\n", err)
		os.Exit(2)
	}
	defer f.Close()

	out := bufio.NewWriter(os.Stdout)
	defer out.Flush()

	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 1<<20), 1<<20)
	for sc.Scan() {
		text := sc.Text()
		q, err := qrcode.New(text, qrcode.Medium)
		if err != nil {
			fmt.Fprintf(out, "ERROR\t%v\nEND\n", err)
			continue
		}
		q.DisableBorder = true
		fmt.Fprintf(out, "RESULT\t%d\n", q.VersionNumber)
		for _, row := range q.Bitmap() {
			line := make([]byte, len(row))
			for i, dark := range row {
				if dark {
					line[i] = '1'
				} else {
					line[i] = '0'
				}
			}
			out.Write(line)
			out.WriteByte('\n')
		}
		out.WriteString("END\n")
	}
}
