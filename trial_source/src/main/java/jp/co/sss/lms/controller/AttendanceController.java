package jp.co.sss.lms.controller;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import jp.co.sss.lms.dto.AttendanceManagementDto;
import jp.co.sss.lms.dto.LoginUserDto;
import jp.co.sss.lms.form.AttendanceForm;
import jp.co.sss.lms.service.StudentAttendanceService;
import jp.co.sss.lms.util.AttendanceUtil;
import jp.co.sss.lms.util.Constants;

/**
 * 勤怠管理コントローラ
 *
 * @author 東京ITスクール
 */
@Controller
@RequestMapping("/attendance")
public class AttendanceController {

	@Autowired
	private StudentAttendanceService studentAttendanceService;

	@Autowired
	private LoginUserDto loginUserDto;

	@Autowired
	private AttendanceUtil attendanceUtil;

	/**
	 * 勤怠管理画面 初期表示
	 *
	 * @param model
	 * @return 勤怠管理画面
	 */
	@RequestMapping(path = "/detail", method = RequestMethod.GET)
	public String index(Model model) throws ParseException {

		// 勤怠一覧の取得
		List<AttendanceManagementDto> attendanceManagementDtoList =studentAttendanceService
				.getAttendanceManagement(loginUserDto.getCourseId(),loginUserDto.getLmsUserId());
		model.addAttribute("attendanceManagementDtoList",attendanceManagementDtoList);

		
		//ヘッダの勤怠項目を押下
		//@author 室月 陽翔 -Task.25
		//@param  modele
		//@return 勤怠管理画面
		//@throw  ParseException
		
		
		// 未入力が有るか、無いかをboolean型で取得、
		// 「UnfilledPast」にService処理の結果(true or false)を代入
		boolean isUnfilledPast =studentAttendanceService.notEnterCheck();
		// スコープで判定結果を格納
		model.addAttribute("isUnfilledPast",isUnfilledPast);

		return "attendance/detail";
	}
	
	

	/**
	 * 勤怠管理画面 『出勤』ボタン押下
	 *
	 * @param model
	 * @return 勤怠管理画面
	 */
	@RequestMapping(path = "/detail",params = "punchIn",method = RequestMethod.POST)
	public String punchIn(Model model) {

		// 更新前のチェック
		String error =studentAttendanceService.punchCheck(Constants.CODE_VAL_ATWORK);
		model.addAttribute("error", error);
		// 勤怠登録
		if (error == null) {
			String message =studentAttendanceService.setPunchIn();
			model.addAttribute("message", message);
		}
		// 一覧の再取得
		List<AttendanceManagementDto> attendanceManagementDtoList =studentAttendanceService
				.getAttendanceManagement(loginUserDto.getCourseId(),loginUserDto.getLmsUserId());
		model.addAttribute("attendanceManagementDtoList",attendanceManagementDtoList);

		return "attendance/detail";
	}

	/**
	 * 勤怠管理画面 『退勤』ボタン押下
	 *
	 * @param model
	 * @return 勤怠管理画面
	 */
	@RequestMapping(path = "/detail",params = "punchOut",method = RequestMethod.POST)
	public String punchOut(Model model) {

		// 更新前のチェック
		String error =studentAttendanceService.punchCheck(Constants.CODE_VAL_LEAVING);
		model.addAttribute("error", error);
		// 勤怠登録
		if (error == null) {
			String message =studentAttendanceService.setPunchOut();
			model.addAttribute("message", message);
		}

		// 一覧の再取得
		List<AttendanceManagementDto> attendanceManagementDtoList =studentAttendanceService
				.getAttendanceManagement(loginUserDto.getCourseId(),loginUserDto.getLmsUserId());
		model.addAttribute("attendanceManagementDtoList",attendanceManagementDtoList);

		return "attendance/detail";
	}

	/**
	 * 勤怠管理画面 『勤怠情報を直接編集する』リンク押下
	 *
	 * @param model
	 * @return 勤怠情報直接変更画面
	 */
	@RequestMapping(path = "/update")
	public String update(Model model) {

		// 勤怠管理リストの取得
		List<AttendanceManagementDto> attendanceManagementDtoList =studentAttendanceService
				.getAttendanceManagement(loginUserDto.getCourseId(),loginUserDto.getLmsUserId());

		// 勤怠フォームの生成
		AttendanceForm attendanceForm =studentAttendanceService
				.setAttendanceForm(attendanceManagementDtoList);
		model.addAttribute("attendanceForm",attendanceForm);

		// 「時」の選択肢（00〜23）
		List<String> hourList = new ArrayList<>();

		for (int i = 0; i < 24; i++) {
			hourList.add(String.format("%02d", i));
		}

		model.addAttribute("hourList", hourList);
		// 「分」の選択肢（00〜59）
		List<String> minuteList = new ArrayList<>();

		for (int i = 0; i < 60; i++) {
			minuteList.add(String.format("%02d", i));
		}

		model.addAttribute("minuteList", minuteList);
		return "attendance/update";
	}

	/**
	 * 勤怠情報直接変更画面 『更新』ボタン押下
	 *
	 * @param attendanceForm
	 * @param model
	 * @param result
	 * @return 勤怠管理画面
	 * @throws ParseException
	 */
	@RequestMapping(path = "/update",params = "complete",method = RequestMethod.POST)
	public String complete(AttendanceForm attendanceForm,BindingResult result,Model model)
			throws ParseException {

		// ★★★★★★★★★★★★★★★★★★★★★★★★★★★
		// 室月 陽翔 - Task.27
		// 勤怠入力チェック
		studentAttendanceService.updateInputCheck(attendanceForm,result);

		// 入力エラーがある場合
		if (result.hasErrors()) {

			// 中抜け時間・時間・分の選択肢を再設定
			attendanceForm.setBlankTimes(attendanceUtil.setBlankTime());
			attendanceForm.setHourMap(attendanceUtil.setHour());
			attendanceForm.setMinuteMap(attendanceUtil.setMinute());

			// 「時」の選択肢（00〜23）
			List<String> hourList = new ArrayList<>();

			for (int i = 0; i < 24; i++) {
				hourList.add(String.format("%02d", i));
			}

			model.addAttribute("hourList", hourList);

			// 「分」の選択肢（00〜59）
			List<String> minuteList = new ArrayList<>();

			for (int i = 0; i < 60; i++) {
				minuteList.add(String.format("%02d", i));
			}

			model.addAttribute("minuteList", minuteList);

			return "attendance/update";
		}

		// 更新
		String message =studentAttendanceService.update(attendanceForm);
		model.addAttribute("message", message);

		// 一覧の再取得
		List<AttendanceManagementDto> attendanceManagementDtoList =studentAttendanceService
				.getAttendanceManagement(loginUserDto.getCourseId(),loginUserDto.getLmsUserId());
		model.addAttribute("attendanceManagementDtoList",attendanceManagementDtoList);

		return "attendance/detail";
	}
}