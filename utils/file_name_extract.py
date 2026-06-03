

def file_name_extract(file_path):
    file_name = file_path.split('/')[-1].split('.')[0]
    return f"generates/videos/{file_name}.mp4", f"generates/images/{file_name}.png", f"uploads/clockwise_videos/{file_name}.mp4"

